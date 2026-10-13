package com.creatorcrm.workflow;

import com.creatorcrm.contacts.Bounces;
import com.creatorcrm.contacts.ContactService;
import com.creatorcrm.contacts.Emails;
import com.creatorcrm.contacts.Suppressions;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.domain.Task;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.CampaignTargetRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounce notices ("Address not found", "Undeliverable"), with plain code (no Claude). For each address she wrote to
 * that bounced:
 * <ul>
 *   <li>the address goes on the do-not-email list, so no pitch or follow-up can go to it again;</li>
 *   <li>each open deal still chasing it stops its follow-ups (saying why) and drops unsent emails to it;</li>
 *   <li>a to-do offers the brand's next best contact, with the pitch to them drafted, or asks her to find one.</li>
 * </ul>
 * Pitch campaigns handle their own bounces ({@code CampaignWatcher}), so campaign deals get no extra to-do.
 */
@Service
public class BounceWatcher {
    private static final Logger log = LoggerFactory.getLogger(BounceWatcher.class);

    private static final String LAST_SCANNED = "bounces.lastScanned";
    /** The first look goes back this far, so bounces that arrived before this existed stop their follow-ups too. */
    static final Duration FIRST_LOOK_BACK = Duration.ofDays(60);
    /** Older bounces still stop follow-ups, but don't spend a Claude call drafting a pitch to someone else. */
    static final Duration DRAFT_WITHIN = Duration.ofDays(14);

    private final MessageRepo messages;
    private final ConversationRepo conversations;
    private final OpportunityRepo opportunities;
    private final BrandContactRepo contactRepo;
    private final ContactService contacts;
    private final Suppressions suppressions;
    private final FollowUpEngine followUps;
    private final DraftRepo drafts;
    private final DraftService draftService;
    private final TaskRepo tasks;
    private final WorkflowEngine workflow;
    private final ActivityRepo activity;
    private final CampaignTargetRepo campaignTargets;
    private final AppStateRepo state;
    private final LlmClient llm;
    private final SettingsService settings;

    public BounceWatcher(MessageRepo messages, ConversationRepo conversations, OpportunityRepo opportunities,
                         BrandContactRepo contactRepo, ContactService contacts, Suppressions suppressions,
                         FollowUpEngine followUps, DraftRepo drafts, DraftService draftService, TaskRepo tasks,
                         WorkflowEngine workflow, ActivityRepo activity, CampaignTargetRepo campaignTargets,
                         AppStateRepo state, LlmClient llm, SettingsService settings) {
        this.messages = messages;
        this.conversations = conversations;
        this.opportunities = opportunities;
        this.contactRepo = contactRepo;
        this.contacts = contacts;
        this.suppressions = suppressions;
        this.followUps = followUps;
        this.drafts = drafts;
        this.draftService = draftService;
        this.tasks = tasks;
        this.workflow = workflow;
        this.activity = activity;
        this.campaignTargets = campaignTargets;
        this.state = state;
        this.llm = llm;
        this.settings = settings;
    }

    /** Looks at every email stored since the last look. Returns how many bounce notices changed something. */
    @Transactional
    public int scan() {
        Optional<Long> cursor = state.findById(LAST_SCANNED).map(s -> s.stateValue).filter(v -> !v.isBlank()).map(Long::valueOf);
        long last = cursor.orElse(0L);
        OffsetDateTime oldest = cursor.isPresent() ? null : OffsetDateTime.now().minus(FIRST_LOOK_BACK);
        int changed = 0;
        for (List<Message> batch = messages.findTop500ByIdGreaterThanOrderByIdAsc(last); !batch.isEmpty();
             batch = messages.findTop500ByIdGreaterThanOrderByIdAsc(last)) {
            for (Message m : batch) {
                last = m.id;
                if (oldest != null && (m.sentAt == null || m.sentAt.isBefore(oldest))) continue;
                try {
                    if (handle(m)) changed++;
                } catch (RuntimeException e) {
                    log.warn("Could not handle bounce notice {}: {}", m.id, e.getMessage());
                }
            }
        }
        AppState s = new AppState();
        s.stateKey = LAST_SCANNED;
        s.stateValue = Long.toString(last);
        state.save(s);
        return changed;
    }

    boolean handle(Message m) {
        if (m.direction != Direction.INBOUND || !Bounces.isBounce(m.sender, m.subject)) return false;
        boolean changed = false;
        // Before bounces skipped Claude, one could be read as the brand writing back and leave a "reply" to-do.
        for (Task t : tasks.findBySourceMessageIdAndStatus(m.id, TaskStatus.OPEN)) {
            workflow.autoClose(t, TaskStatus.DISMISSED, "That email was a delivery failure notice, not a message from the brand");
            changed = true;
        }
        Set<String> failed = new LinkedHashSet<>();
        for (String addr : Bounces.addressesIn(m.subject, m.content)) {
            if (!messages.conversationsWrittenTo(like(addr)).isEmpty()) failed.add(addr);
        }
        for (String addr : failed) {
            contacts.doNotEmail(addr, Suppression.Reason.BOUNCED);
            Set<Long> convIds = new LinkedHashSet<>();
            if (m.conversationId != null) convIds.add(m.conversationId);
            convIds.addAll(messages.conversationsWrittenTo(like(addr)));
            Set<Long> seen = new LinkedHashSet<>();
            for (Long convId : convIds) {
                if (convId == null) continue;
                Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(convId).orElse(null);
                if (o == null || !o.status.isOpen() || !seen.add(o.id)) continue;
                contacts.refreshPrimary(o.brandId);
                onBounced(o, addr, m);
            }
            changed = true;
        }
        return changed;
    }

    private void onBounced(Opportunity o, String addr, Message notice) {
        conversations.findById(o.conversationId).ifPresent(c -> fixCounterparty(c, addr));
        for (Draft d : drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING)) {
            if (Emails.findAll(d.toAddress).contains(addr)) {
                d.status = DraftStatus.SUPERSEDED;
                drafts.save(d);
            }
        }
        String next = draftService.nextEmailTo(o).orElse(null);
        boolean chasingIt = next == null || Emails.findAll(next).contains(addr) || suppressions.blocked(next);
        if (!chasingIt) return; // someone else at the brand wrote back; the deal carries on with them
        String brand = workflow.brandName(o);
        boolean stopped = followUps.stop(o, "Emails to " + addr + " bounced");
        activity.save(Activity.of(o.id, Activity.STATUS_CHANGED, "Email to " + addr + " bounced"));
        if (campaignTargets.findFirstByOpportunityIdOrderByIdDesc(o.id).isPresent()) return;

        BrandContact other = contactRepo.findByBrandIdOrderByScoreDescIdAsc(o.brandId).stream()
                .filter(BrandContact::emailable).filter(c -> !c.email.equals(addr) && !suppressions.blocked(c.email))
                .findFirst().orElse(null);
        String description = other != null
                ? "Emails to " + addr + " bounced. Write to " + other.email + " at " + brand + " instead"
                : "Emails to " + addr + " bounced. Find another email for " + brand;
        boolean asked = tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN).stream()
                .anyMatch(t -> t.description != null && t.description.startsWith("Emails to " + addr + " bounced"));
        if (asked) return;
        Task t = new Task();
        t.opportunityId = o.id;
        t.type = TaskType.OTHER;
        t.description = description;
        t.priority = Priority.HIGH;
        t.dueDate = settings.today();
        t.status = TaskStatus.OPEN;
        t.sourceMessageId = notice.id;
        t.createdAt = OffsetDateTime.now();
        String stoppedLine = stopped ? " Its follow-ups are stopped, and " : " ";
        t.brief = "Your email to " + addr + " came back undelivered." + stoppedLine
                + addr + " is on your do-not-email list now. "
                + (other != null
                        ? (other.name != null && !other.name.isBlank() ? other.name + " (" + other.email + ")" : other.email)
                                + " is the next best contact at " + brand + ". Send them the pitch, and follow-ups start again from there."
                        : "There's no other address for " + brand + " yet: look on their website or use the contact finders on the brand's contacts.");
        t = tasks.save(t);

        if (other == null || !llm.isConfigured() || notice.sentAt == null
                || notice.sentAt.isBefore(OffsetDateTime.now().minus(DRAFT_WITHIN))) return;
        try {
            if (draftService.draftAfterBounce(o.id, addr, other.email, t.id).isPresent()) {
                t.brief = t.brief + " The pitch to " + other.email + " is ready in Drafts.";
                tasks.save(t);
            }
        } catch (RuntimeException e) {
            log.warn("Could not draft to {} for deal {}: {}", other.email, o.id, e.getMessage());
        }
    }

    /**
     * Before the morning drafts a follow-up: when the deal's email would go to an address that bounced, opted out or
     * doesn't exist, stop the follow-ups instead (saying why). True when it was stopped.
     */
    @Transactional
    public boolean stopIfUnreachable(FollowUp f) {
        Opportunity o = opportunities.findById(f.opportunityId).orElse(null);
        if (o == null) return false;
        String next = draftService.nextEmailTo(o).orElse(null);
        if (next == null) return false;
        Optional<String> why = Emails.findAll(next).stream().map(suppressions::whyNot).flatMap(Optional::stream).findFirst();
        if (why.isEmpty()) return false;
        return followUps.stop(o, why.get().replaceFirst(",? so this can't be sent\\..*$", ""));
    }

    /** An earlier bounce notice may have become the thread's "other party"; the bounced address is who she wrote to. */
    private void fixCounterparty(Conversation c, String addr) {
        if (c.counterparty == null || c.counterparty.isBlank() || Bounces.isBounce(c.counterparty, null)) {
            c.counterparty = addr;
            conversations.save(c);
        }
    }

    private static String like(String addr) {
        return "%" + addr + "%";
    }
}
