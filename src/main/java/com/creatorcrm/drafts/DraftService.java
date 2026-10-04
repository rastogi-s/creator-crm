package com.creatorcrm.drafts;

import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.learning.LearningService;
import com.creatorcrm.llm.DraftInput;
import com.creatorcrm.llm.DraftText;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.Untrusted;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI drafts and the human approval queue. Nothing is sent unless {@link #send} is called for that
 * specific draft, which only happens from an explicit click in the dashboard, or the creator has opted in
 * to automatic email follow-ups ({@link #sendFollowUpAutomatically}).
 */
@Service
public class DraftService {
    private static final Logger log = LoggerFactory.getLogger(DraftService.class);
    private static final int CONTEXT_MESSAGES = 6;

    private final LlmClient llm;
    private final Map<Platform, ChannelConnector> channels;
    private final DraftRepo drafts;
    private final OpportunityRepo opportunities;
    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final BrandRepo brands;
    private final ActivityRepo activity;
    private final WorkflowEngine workflow;
    private final SettingsService settings;
    private final LearningService learning;

    public DraftService(LlmClient llm, List<ChannelConnector> connectors, DraftRepo drafts,
                        OpportunityRepo opportunities, ConversationRepo conversations, MessageRepo messages,
                        BrandRepo brands, ActivityRepo activity, WorkflowEngine workflow, SettingsService settings,
                        LearningService learning) {
        this.llm = llm;
        this.channels = connectors.stream().collect(Collectors.toMap(ChannelConnector::platform, Function.identity()));
        this.drafts = drafts;
        this.opportunities = opportunities;
        this.conversations = conversations;
        this.messages = messages;
        this.brands = brands;
        this.activity = activity;
        this.workflow = workflow;
        this.settings = settings;
        this.learning = learning;
    }

    public Optional<Draft> draftForTask(Task t) {
        DraftType type = switch (t.type) {
            case REPLY, CONFIRM_AVAILABILITY -> DraftType.REPLY;
            case SEND_RATES -> DraftType.RATES;
            case SEND_MEDIA_KIT -> DraftType.MEDIA_KIT;
            case NEGOTIATE -> DraftType.NEGOTIATION;
            case ASK_MISSING_INFO -> DraftType.ASK_DETAILS;
            case CONFIRM_PRODUCT -> DraftType.PRODUCT_ARRIVAL;
            default -> null;
        };
        if (type == null || t.opportunityId == null || drafts.existsByTaskIdAndStatus(t.id, DraftStatus.PENDING)) {
            return Optional.empty();
        }
        return Optional.of(generate(t.opportunityId, type, "Task: " + t.description, t.id, null));
    }

    public Optional<Draft> draftForFollowUp(FollowUp fu) {
        if (drafts.existsByFollowupIdAndStatus(fu.id, DraftStatus.PENDING)) return Optional.empty();
        int max = settings.maxFollowups();
        String note = "This is follow-up #" + fu.number + " of " + max + " (no reply yet)."
                + (fu.number >= max ? " It is the final follow-up: politely close the loop and leave the door open." : "");
        return Optional.of(generate(fu.opportunityId, DraftType.FOLLOW_UP, note, null, fu.id));
    }

    @Transactional
    public Draft generate(Long opportunityId, DraftType type, String instructions, Long taskId, Long followupId) {
        Opportunity o = opportunities.findById(opportunityId).orElseThrow(() -> new IllegalArgumentException("Unknown opportunity"));
        Brand b = brands.findById(o.brandId).orElseThrow();
        Conversation conv = o.conversationId == null ? null : conversations.findById(o.conversationId).orElse(null);
        List<Message> thread = conv == null ? List.of() : messages.findByConversationIdOrderBySentAtAsc(conv.id);

        Draft d = new Draft();
        d.opportunityId = o.id;
        d.conversationId = conv == null ? null : conv.id;
        d.taskId = taskId;
        d.followupId = followupId;
        d.type = type;
        route(d, b, conv, thread);

        String extra = (instructions == null ? "" : instructions)
                + (o.missingInfo == null || o.missingInfo.isBlank() ? "" : " Still unknown in this deal: " + o.missingInfo + ".");
        List<Message> recent = thread.subList(Math.max(0, thread.size() - CONTEXT_MESSAGES), thread.size());
        DraftText text = llm.writeDraft(new DraftInput(settings.today(), type.name(), d.channel.name(), b.name,
                b.contactName == null ? "" : b.contactName, dealRecord(o),
                conv == null || conv.summary == null ? "" : conv.summary,
                recent.stream().map(Untrusted::wrap).toList(), extra.strip(),
                learning.examplesFor(type.name(), d.channel, o.id)));

        d.subject = d.channel == Platform.EMAIL
                ? (text.subject().isBlank() ? d.subject : text.subject()) : "";
        d.body = text.body();
        d.originalSubject = d.subject;
        d.originalBody = d.body;
        d.status = DraftStatus.PENDING;
        d.createdAt = OffsetDateTime.now();
        d = drafts.save(d);

        ChannelConnector channel = channels.get(d.channel);
        if (channel != null && channel.isConnected()) {
            try {
                Draft saved = d;
                channel.pushDraft(d).ifPresent(id -> saved.gmailDraftId = id);
                d = drafts.save(saved);
            } catch (Exception e) {
                log.warn("Could not mirror draft {} to {}: {}", d.id, d.channel, e.getMessage());
            }
        }
        return d;
    }

    /** Pick channel, recipient and threading headers from the conversation (or brand contact for new threads). */
    private static void route(Draft d, Brand b, Conversation conv, List<Message> thread) {
        if (conv != null) {
            d.channel = conv.platform;
            if (conv.platform == Platform.EMAIL) {
                Message lastIn = thread.stream().filter(m -> m.direction == Direction.INBOUND)
                        .reduce((a, x) -> x).orElse(null);
                Message last = thread.isEmpty() ? null : thread.get(thread.size() - 1);
                d.toAddress = lastIn == null ? conv.counterparty
                        : (lastIn.replyTo != null && !lastIn.replyTo.isBlank() ? lastIn.replyTo : lastIn.sender);
                d.inReplyTo = last == null ? null : last.rfcMessageId;
                d.gmailThreadId = conv.externalId;
                String subj = conv.subject == null ? "" : conv.subject;
                d.subject = subj.regionMatches(true, 0, "re:", 0, 3) ? subj : "Re: " + subj;
            } else {
                d.toAddress = conv.counterparty;
            }
            return;
        }
        if (b.contactEmail != null && !b.contactEmail.isBlank()) {
            d.channel = Platform.EMAIL;
            d.toAddress = b.contactEmail;
            d.subject = "";
        } else if (b.instagram != null && !b.instagram.isBlank()) {
            d.channel = Platform.INSTAGRAM;
            d.toAddress = b.instagram;
        } else {
            throw new IllegalStateException("No email or Instagram contact for " + b.name + ". Add one first.");
        }
    }

    /** Why this draft can't be sent via API right now (e.g. outside Instagram's 24h window), if anything. */
    public Optional<String> sendBlockedReason(Draft d) {
        ChannelConnector c = channels.get(d.channel);
        if (c == null || !c.isConnected()) return Optional.of(d.channel + " is not connected.");
        OffsetDateTime lastInbound = d.conversationId == null ? null
                : messages.findByConversationIdOrderBySentAtAsc(d.conversationId).stream()
                        .filter(m -> m.direction == Direction.INBOUND).map(m -> m.sentAt)
                        .reduce((a, x) -> x).orElse(null);
        return c.sendBlockedReason(d, lastInbound);
    }

    /** Human-approved send of one specific draft, optionally with edits. */
    @Transactional
    public Draft send(Long draftId, String editedSubject, String editedBody) {
        Draft d = pending(draftId);
        if (editedBody != null && !editedBody.isBlank()) d.body = editedBody.strip();
        if (editedSubject != null && d.channel == Platform.EMAIL) d.subject = editedSubject.strip();
        return deliver(d, false);
    }

    /**
     * Opt-in automatic send, used by the daily follow-up run. Only email follow-ups qualify: anything else
     * (replies, rates, Instagram DMs) always waits for the creator's click.
     */
    @Transactional
    public Draft sendFollowUpAutomatically(Long draftId) {
        Draft d = pending(draftId);
        if (d.type != DraftType.FOLLOW_UP || d.channel != Platform.EMAIL) {
            throw new IllegalStateException("Only email follow-ups can be sent automatically");
        }
        return deliver(d, true);
    }

    private Draft deliver(Draft d, boolean automatic) {
        sendBlockedReason(d).ifPresent(reason -> { throw new IllegalStateException(reason); });
        try {
            ChannelConnector.SentMessage sent = channels.get(d.channel).send(d);
            recordOutbound(d, sent, automatic);
            d.status = DraftStatus.SENT;
            d.sentAt = OffsetDateTime.now();
            d.error = null;
        } catch (Exception e) {
            d.error = e.getMessage();
            drafts.save(d);
            throw new IllegalStateException("Sending failed: " + e.getMessage(), e);
        }
        return drafts.save(d);
    }

    /** The creator sent it themselves (e.g. from the Instagram app). Apply the same workflow effects. */
    @Transactional
    public Draft markSentManually(Long draftId) {
        Draft d = pending(draftId);
        recordOutbound(d, null, false);
        d.status = DraftStatus.SENT;
        d.sentAt = OffsetDateTime.now();
        return drafts.save(d);
    }

    @Transactional
    public Draft discard(Long draftId) {
        Draft d = pending(draftId);
        d.status = DraftStatus.DISCARDED;
        return drafts.save(d);
    }

    @Transactional
    public Draft edit(Long draftId, String subject, String body) {
        Draft d = pending(draftId);
        if (body != null && !body.isBlank()) d.body = body.strip();
        if (subject != null && d.channel == Platform.EMAIL) d.subject = subject.strip();
        return drafts.save(d);
    }

    private Draft pending(Long id) {
        Draft d = drafts.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown draft"));
        if (d.status != DraftStatus.PENDING) throw new IllegalStateException("Draft is " + d.status + ", not pending");
        return d;
    }

    private void recordOutbound(Draft d, ChannelConnector.SentMessage sent, boolean automatic) {
        Opportunity o = opportunities.findById(d.opportunityId).orElseThrow();
        if (sent != null) {
            Conversation conv = d.conversationId == null ? null : conversations.findById(d.conversationId).orElse(null);
            if (conv == null) {
                conv = new Conversation();
                conv.platform = d.channel;
                conv.externalId = sent.threadKey();
                conv.counterparty = d.toAddress;
                conv.subject = d.subject;
                conv.brandId = o.brandId;
                conv.brandRelated = true;
                conv.createdAt = OffsetDateTime.now();
                conv = conversations.save(conv);
                o.conversationId = conv.id;
            }
            conv.lastMessageAt = OffsetDateTime.now();
            conversations.save(conv);
            Message m = new Message();
            m.conversationId = conv.id;
            m.externalId = d.channel.name().toLowerCase() + ":" + sent.externalId();
            m.direction = Direction.OUTBOUND;
            m.recipient = d.toAddress;
            m.subject = d.subject;
            m.content = d.body;
            m.sentAt = OffsetDateTime.now();
            m.aiProcessed = true; // we already know what it is
            m.messageType = intentOf(d.type).name();
            messages.save(m);
        }
        if (d.type == DraftType.PITCH && o.pitchedAt == null) o.pitchedAt = settings.today();
        workflow.onCreatorMessage(o, intentOf(d.type), settings.today());
        learning.recordDraftSent(d, workflow.brandName(o));
        activity.save(Activity.of(o.id, Activity.DRAFT_SENT,
                d.type.name().toLowerCase().replace('_', ' ') + (automatic ? " sent automatically to " : " sent to ")
                        + workflow.brandName(o)));
    }

    private static Intent intentOf(DraftType type) {
        return switch (type) {
            case FOLLOW_UP -> Intent.CREATOR_FOLLOW_UP;
            case PITCH -> Intent.PITCH;
            case DECLINE -> Intent.CREATOR_DECLINED;
            case RATES, MEDIA_KIT -> Intent.SENT_RATES_OR_MEDIA_KIT;
            case CONTENT_SUBMISSION -> Intent.CONTENT_SUBMITTED;
            default -> Intent.CREATOR_REPLY;
        };
    }

    private static String dealRecord(Opportunity o) {
        return "status=" + o.status.label + "; type=" + o.type + "; compensation=" + o.compensation
                + (o.budgetText == null || o.budgetText.isBlank() ? "" : "; budget=" + o.budgetText)
                + (o.deliverables == null || o.deliverables.isBlank() ? "" : "; deliverables=" + o.deliverables)
                + (o.usageRights == null || o.usageRights.isBlank() ? "" : "; usage rights=" + o.usageRights)
                + (o.campaign == null || o.campaign.isBlank() ? "" : "; campaign=" + o.campaign);
    }
}
