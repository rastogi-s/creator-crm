package com.creatorcrm.drafts;

import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.contacts.Emails;
import com.creatorcrm.contacts.Suppressions;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Attachment;
import com.creatorcrm.domain.CampaignResult;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.invoices.InvoicePdf;
import com.creatorcrm.repo.CampaignResultRepo;
import com.creatorcrm.results.ResultsPdf;
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
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final InvoiceRepo invoices;
    private final CampaignResultRepo results;
    private final ResultsPdf resultsPdf;
    private final InvoicePdf invoicePdf;
    /** Practice mode (sample brands): Send only pretends. See {@code PracticeMode}. */
    @Value("${crm.practice:false}")
    boolean practice;

    private final Suppressions suppressions;

    public DraftService(LlmClient llm, List<ChannelConnector> connectors, DraftRepo drafts,
                        OpportunityRepo opportunities, ConversationRepo conversations, MessageRepo messages,
                        BrandRepo brands, ActivityRepo activity, WorkflowEngine workflow, SettingsService settings,
                        LearningService learning, InvoiceRepo invoices, InvoicePdf invoicePdf,
                        CampaignResultRepo results, ResultsPdf resultsPdf, Suppressions suppressions) {
        this.suppressions = suppressions;
        this.results = results;
        this.resultsPdf = resultsPdf;
        this.invoices = invoices;
        this.invoicePdf = invoicePdf;
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
        if (type == DraftType.INVOICE || type == DraftType.PAYMENT_REMINDER) {
            throw new IllegalArgumentException("Invoices and payment reminders are written from the invoice itself");
        }
        return create(opportunityId, type, instructions, taskId, followupId, null, null, null);
    }

    /**
     * An email about an invoice ({@link DraftType#INVOICE} or {@link DraftType#PAYMENT_REMINDER}): the PDF goes out
     * attached, to {@code invoice.billToEmail} when set. If Claude can't write the note (no API key, outage),
     * {@code fallback} is used so invoicing never depends on AI. Replaces a pending draft of the same kind.
     */
    @Transactional
    public Draft generateInvoiceEmail(Invoice invoice, DraftType type, String instructions, DraftText fallback) {
        for (Draft old : drafts.findByOpportunityIdAndStatus(invoice.opportunityId, DraftStatus.PENDING)) {
            if (invoice.id.equals(old.invoiceId) && old.type == type) {
                old.status = DraftStatus.SUPERSEDED;
                drafts.save(old);
            }
        }
        return create(invoice.opportunityId, type, instructions, null, null, invoice, null, fallback);
    }

    /**
     * A results recap ({@link DraftType#RESULTS_RECAP}) to the brand, with the campaign results PDF attached when it
     * goes by email. {@code fallback} keeps it working without Claude. Replaces an unsent recap for the same deal.
     */
    @Transactional
    public Draft generateRecap(CampaignResult result, String instructions, DraftText fallback) {
        for (Draft old : drafts.findByOpportunityIdAndStatus(result.opportunityId, DraftStatus.PENDING)) {
            if (old.type == DraftType.RESULTS_RECAP) {
                old.status = DraftStatus.SUPERSEDED;
                drafts.save(old);
            }
        }
        return create(result.opportunityId, DraftType.RESULTS_RECAP, instructions, null, null, null, result, fallback);
    }

    /** The one-page results PDF for a deal's campaign. */
    public byte[] resultsPdf(CampaignResult r) {
        Opportunity o = opportunities.findById(r.opportunityId).orElseThrow();
        return resultsPdf.render(r, o, workflow.brandName(o));
    }

    private Draft create(Long opportunityId, DraftType type, String instructions, Long taskId, Long followupId,
                         Invoice invoice, CampaignResult result, DraftText fallback) {
        Opportunity o = opportunities.findById(opportunityId).orElseThrow(() -> new IllegalArgumentException("Unknown opportunity"));
        Brand b = brands.findById(o.brandId).orElseThrow();
        Conversation conv = o.conversationId == null ? null : conversations.findById(o.conversationId).orElse(null);
        List<Message> thread = conv == null ? List.of() : messages.findByConversationIdOrderBySentAtAsc(conv.id);

        Draft d = new Draft();
        d.opportunityId = o.id;
        d.conversationId = conv == null ? null : conv.id;
        d.taskId = taskId;
        d.followupId = followupId;
        d.resultId = result == null ? null : result.id;
        d.type = type;
        if (invoice != null) {
            d.invoiceId = invoice.id;
            routeInvoice(d, invoice, b, conv, thread);
        } else if (type == DraftType.REPITCH) {
            routeRepitch(d, b, conv, thread);
        } else {
            route(d, b, conv, thread);
        }

        DraftInput input = draftInput(o, b, conv, thread, type, d.channel, instructions);
        DraftText text;
        try {
            text = llm.writeDraft(input);
        } catch (RuntimeException e) {
            if (fallback == null) throw e;
            log.info("Using the standard {} email for deal {}: {}", type, o.id, e.getMessage());
            text = fallback;
        }

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
                Draft saved = attach(d);
                channel.pushDraft(saved).ifPresent(id -> saved.gmailDraftId = id);
                d = drafts.save(saved);
            } catch (Exception e) {
                log.warn("Could not mirror draft {} to {}: {}", d.id, d.channel, e.getMessage());
            }
        }
        return d;
    }

    private DraftInput draftInput(Opportunity o, Brand b, Conversation conv, List<Message> thread, DraftType type,
                                  Platform channel, String instructions) {
        String extra = (instructions == null ? "" : instructions)
                + (o.missingInfo == null || o.missingInfo.isBlank() ? "" : " Still unknown in this deal: " + o.missingInfo + ".");
        List<Message> recent = thread.subList(Math.max(0, thread.size() - CONTEXT_MESSAGES), thread.size());
        return new DraftInput(settings.today(), type.name(), channel.name(), b.name,
                b.contactName == null ? "" : b.contactName, dealRecord(o),
                conv == null || conv.summary == null ? "" : conv.summary,
                recent.stream().map(Untrusted::wrap).toList(), extra.strip(),
                learning.examplesFor(type.name(), channel, o.id));
    }

    /**
     * Claude rewrites a pending draft the way the creator asks ("make it warmer"), starting from the text she sees,
     * unsaved edits included. The new text is saved like a manual edit: the draft still waits for her Send, and
     * the original Claude draft stays as it was, so learning treats the change as hers.
     */
    @Transactional
    public Draft revise(Long draftId, String subject, String body, String request) {
        Draft d = pending(draftId);
        if (request == null || request.isBlank()) throw new IllegalArgumentException("Say what to change");
        Opportunity o = opportunities.findById(d.opportunityId).orElseThrow(() -> new IllegalArgumentException("Unknown opportunity"));
        Brand b = brands.findById(o.brandId).orElseThrow();
        Conversation conv = d.conversationId == null ? null : conversations.findById(d.conversationId).orElse(null);
        List<Message> thread = conv == null ? List.of() : messages.findByConversationIdOrderBySentAtAsc(conv.id);
        DraftText current = new DraftText(
                d.channel == Platform.EMAIL ? (subject != null ? subject : d.subject == null ? "" : d.subject).strip() : "",
                (body != null && !body.isBlank() ? body : d.body).strip());

        DraftText text = llm.reviseDraft(draftInput(o, b, conv, thread, d.type, d.channel, null), current, request.strip());
        if (text.body() == null || text.body().isBlank()) throw new IllegalStateException("Claude returned an empty message; try again");
        d.body = text.body().strip();
        if (d.channel == Platform.EMAIL) {
            d.subject = text.subject() == null || text.subject().isBlank() ? current.subject() : text.subject().strip();
        }
        return drafts.save(d);
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

    /**
     * A win-back re-pitch starts a fresh conversation with the brand's contact, since the old deal's thread is
     * finished. Without a saved contact it falls back to replying in that old thread.
     */
    private static void routeRepitch(Draft d, Brand b, Conversation conv, List<Message> thread) {
        boolean hasContact = (b.contactEmail != null && !b.contactEmail.isBlank()) || (b.instagram != null && !b.instagram.isBlank());
        if (hasContact || conv == null) {
            route(d, b, null, thread);
            d.conversationId = null;
        } else {
            route(d, b, conv, thread);
        }
    }

    /** An invoice goes by email: in the deal's email thread when there is one, else as a new email. */
    private static void routeInvoice(Draft d, Invoice inv, Brand b, Conversation conv, List<Message> thread) {
        String email = inv.billToEmail != null && !inv.billToEmail.isBlank() ? inv.billToEmail.strip()
                : b.contactEmail != null && !b.contactEmail.isBlank() ? b.contactEmail.strip() : null;
        if (conv != null && conv.platform == Platform.EMAIL) {
            route(d, b, conv, thread);
            if (email != null) d.toAddress = email;
            return;
        }
        if (email == null) {
            throw new IllegalStateException("Add an email address for " + b.name + " on the invoice first.");
        }
        d.channel = Platform.EMAIL;
        d.toAddress = email;
        d.subject = "Invoice " + inv.number;
    }

    /** Loads the files this draft carries (the invoice PDF) so the channel can attach them. */
    private Draft attach(Draft d) {
        if (d.invoiceId != null && d.channel == Platform.EMAIL) {
            Invoice inv = invoices.findById(d.invoiceId).orElseThrow(() -> new IllegalStateException("The invoice was deleted"));
            d.attachments = List.of(new Attachment(inv.number + ".pdf", "application/pdf", invoicePdf.render(inv)));
        } else if (d.resultId != null && d.channel == Platform.EMAIL) {
            CampaignResult r = results.findById(d.resultId).orElseThrow(() -> new IllegalStateException("The campaign results were deleted"));
            d.attachments = List.of(new Attachment(ResultsPdf.fileName(workflow.brandName(opportunities.findById(d.opportunityId).orElseThrow())),
                    "application/pdf", resultsPdf(r)));
        }
        return d;
    }

    /** Why this draft can't be sent via API right now (e.g. outside Instagram's 24h window), if anything. */
    /** Drafts that reach out first or chase, which must never go to someone on the do-not-email list or a dead address. */
    private static final Set<DraftType> OUTREACH = EnumSet.of(DraftType.PITCH, DraftType.REPITCH, DraftType.FOLLOW_UP);

    public Optional<String> sendBlockedReason(Draft d) {
        if (practice) return Optional.empty(); // nothing really goes out, so nothing can stop it
        if (d.channel == Platform.EMAIL && OUTREACH.contains(d.type)) {
            Optional<String> stop = Emails.findAll(d.toAddress).stream().map(suppressions::whyNot)
                    .flatMap(Optional::stream).findFirst();
            if (stop.isPresent()) return stop;
        }
        ChannelConnector c = channels.get(d.channel);
        if (c == null || !c.isConnected()) return Optional.of((d.channel == Platform.EMAIL ? "Gmail" : d.channel == Platform.INSTAGRAM ? "Instagram" : "This account")
                + " isn't connected, so this can't be sent from here. Connect it in Settings, or copy it and press I sent it myself.");
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
     * Saves her edits and checks the draft could go out now, without sending it. Used before the short undo window
     * ({@link SendQueue}) so a draft that can't be sent says so straight away, not after the wait.
     */
    @Transactional
    public Draft readyToSend(Long draftId, String editedSubject, String editedBody) {
        Draft d = edit(draftId, editedSubject, editedBody);
        sendBlockedReason(d).ifPresent(reason -> { throw new IllegalStateException(reason); });
        String blanks = Placeholders.message(d.body);
        if (blanks != null) throw new IllegalStateException(blanks);
        return d;
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
        // Body only: subjects often carry tags like "[EXTERNAL]" from the brand's mail system.
        String blanks = Placeholders.message(d.body);
        if (blanks != null) throw new IllegalStateException(blanks);
        if (practice) {
            // Practice mode: the deal moves on exactly as after a real send, but nothing leaves the app.
            recordOutbound(d, null, automatic);
            d.status = DraftStatus.SENT;
            d.sentAt = OffsetDateTime.now();
            d.error = null;
            return drafts.save(d);
        }
        try {
            ChannelConnector.SentMessage sent = channels.get(d.channel).send(attach(d));
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
        Opportunity o = d.type == DraftType.REPITCH ? rebook(d) : opportunities.findById(d.opportunityId).orElseThrow();
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
        if ((d.type == DraftType.PITCH || d.type == DraftType.REPITCH) && o.pitchedAt == null) o.pitchedAt = settings.today();
        if (d.invoiceId != null) {
            invoices.findById(d.invoiceId).ifPresent(inv -> {
                if (d.type == DraftType.PAYMENT_REMINDER) recordReminder(inv);
                else markInvoiceSent(inv);
            });
        }
        workflow.onCreatorMessage(o, intentOf(d.type), settings.today());
        learning.recordDraftSent(d, workflow.brandName(o));
        activity.save(Activity.of(o.id, Activity.DRAFT_SENT,
                d.type.name().toLowerCase().replace('_', ' ') + (automatic ? " sent automatically to " : " sent to ")
                        + workflow.brandName(o)));
    }

    /**
     * A re-pitch was sent: it starts a new deal with the brand (the old collab stays as it was), so replies,
     * follow-ups and the pipeline track it like any other pitch. The draft moves to the new deal.
     */
    private Opportunity rebook(Draft d) {
        Opportunity last = opportunities.findById(d.opportunityId).orElseThrow();
        Opportunity o = new Opportunity();
        o.brandId = last.brandId;
        o.conversationId = d.conversationId;
        o.origin = Origin.PITCH;
        o.type = OpportunityType.OTHER;
        o.compensation = Compensation.UNKNOWN;
        o.status = OpportunityStatus.NEW_LEAD;
        String before = last.campaign != null && !last.campaign.isBlank() ? last.campaign
                : last.deliverables != null && !last.deliverables.isBlank() ? last.deliverables : null;
        o.campaign = "Working together again" + (before == null ? "" : " after " + before);
        if (o.campaign.length() > 200) o.campaign = o.campaign.substring(0, 200);
        o.pitchPlatform = d.channel.name();
        o.createdAt = OffsetDateTime.now();
        o.updatedAt = o.createdAt;
        o = opportunities.save(o);
        activity.save(Activity.of(o.id, Activity.NEW_OPPORTUNITY, "Re-pitched " + workflow.brandName(o)));
        d.opportunityId = o.id;
        return o;
    }

    private void markInvoiceSent(Invoice inv) {
        if (inv.status != InvoiceStatus.DRAFT) return;
        inv.status = InvoiceStatus.SENT;
        inv.sentAt = OffsetDateTime.now();
        invoices.save(inv);
    }

    private void recordReminder(Invoice inv) {
        inv.remindersSent++;
        inv.lastReminderOn = settings.today();
        invoices.save(inv);
    }

    private static Intent intentOf(DraftType type) {
        return switch (type) {
            case INVOICE, PAYMENT_REMINDER -> Intent.INVOICE_SENT;
            case FOLLOW_UP -> Intent.CREATOR_FOLLOW_UP;
            case PITCH, REPITCH -> Intent.PITCH;
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
