package com.creatorcrm.workflow;

import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.MessageAnalysis;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an AI message analysis to the CRM with fixed rules: creates/updates the opportunity, moves its
 * status, creates tasks and deadlines, and drives the follow-up schedule.
 */
@Service
public class WorkflowEngine {

    /** Result: tasks that would benefit from a drafted reply. */
    public record Outcome(Long opportunityId, List<Task> tasksToDraft) {
        static Outcome none() { return new Outcome(null, List.of()); }
    }

    private final BrandRepo brands;
    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final OpportunityRepo opportunities;
    private final TaskRepo tasks;
    private final DeadlineRepo deadlines;
    private final DraftRepo drafts;
    private final ActivityRepo activity;
    private final FollowUpEngine followUps;

    public WorkflowEngine(BrandRepo brands, ConversationRepo conversations, MessageRepo messages,
                          OpportunityRepo opportunities, TaskRepo tasks, DeadlineRepo deadlines, DraftRepo drafts,
                          ActivityRepo activity, FollowUpEngine followUps) {
        this.brands = brands;
        this.conversations = conversations;
        this.messages = messages;
        this.opportunities = opportunities;
        this.tasks = tasks;
        this.deadlines = deadlines;
        this.drafts = drafts;
        this.activity = activity;
        this.followUps = followUps;
    }

    @Transactional
    public Outcome apply(Message m, Conversation conv, MessageAnalysis a) {
        m.aiProcessed = true;
        m.messageType = a.intent().name();
        messages.save(m);
        if (!a.updatedSummary().isBlank()) conv.summary = a.updatedSummary();
        conv.brandRelated = Boolean.TRUE.equals(conv.brandRelated) || a.brandRelated();

        Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(conv.id).orElse(null);
        if (o == null && !a.brandRelated()) {
            conversations.save(conv);
            return Outcome.none();
        }
        if (o == null) {
            o = createOpportunity(m, conv, a);
        }
        mergeFacts(o, a);
        LocalDate day = m.sentAt.toLocalDate();

        List<Task> toDraft = new ArrayList<>();
        if (m.direction == Direction.INBOUND) {
            if (followUps.onBrandReply(o)) {
                activity.save(Activity.of(o.id, Activity.BRAND_REPLIED, brandName(o) + " replied"));
                if (o.origin == Origin.PITCH && (o.initialResponse == null || o.initialResponse.isBlank())) {
                    o.initialResponse = a.intent() + (a.suggestedAction().isBlank() ? "" : ": " + a.suggestedAction());
                }
            }
            IntentRules.Rule rule = IntentRules.of(a.intent());
            if (rule.status() != null) {
                setStatus(o, rule.status());
            } else if (a.requiresReply() && (o.status == OpportunityStatus.PITCHED
                    || o.status == OpportunityStatus.NEW_LEAD || o.status == OpportunityStatus.FOLLOW_UP_NEEDED)) {
                setStatus(o, OpportunityStatus.AWAITING_MY_REPLY);
            }
            if (rule.status() == OpportunityStatus.CLOSED) {
                o.closedReason = "Declined by brand";
                closeOpenWork(o);
            } else {
                TaskType type = rule.task() != null ? rule.task() : (a.requiresReply() ? TaskType.REPLY : null);
                if (type != null) {
                    Priority p = a.intent() == Intent.BRAND_FOLLOW_UP ? Priority.HIGH : a.urgency();
                    Task t = upsertTask(o, type, describe(o, type, a), p, dueFor(type, a, day), m.id);
                    if (TaskType.ANSWERED_BY_OUTBOUND.contains(type)) toDraft.add(t);
                }
            }
        } else {
            onCreatorMessage(o, a.intent(), day);
        }

        addDeadlines(o, a, day);
        supersedePendingDrafts(o);
        o.updatedAt = OffsetDateTime.now();
        opportunities.save(o);
        conversations.save(conv);
        return new Outcome(o.id, toDraft);
    }

    /** Effects of the creator writing to the brand (from ingestion or from sending an approved draft). */
    @Transactional
    public void onCreatorMessage(Opportunity o, Intent intent, LocalDate day) {
        for (Task t : tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN)) {
            if (TaskType.ANSWERED_BY_OUTBOUND.contains(t.type)) completeTask(t);
        }
        IntentRules.Rule rule = IntentRules.of(intent);
        if (rule.status() != null && (o.status.isOpen() || intent == Intent.PITCH)) {
            OpportunityStatus target = rule.status();
            if (intent == Intent.CONTENT_POSTED && o.compensation == Compensation.PAID) target = OpportunityStatus.PAYMENT_PENDING;
            if (intent != Intent.PITCH || o.status == OpportunityStatus.NEW_LEAD) setStatus(o, target);
        }
        if (intent == Intent.CREATOR_DECLINED) {
            o.closedReason = "Declined by creator";
            closeOpenWork(o);
        } else if (intent != Intent.CONTENT_POSTED) {
            followUps.onCreatorMessage(o, day, intent == Intent.CREATOR_FOLLOW_UP);
        }
        o.updatedAt = OffsetDateTime.now();
        opportunities.save(o);
    }

    @Transactional
    public void completeTask(Task t) {
        t.status = TaskStatus.DONE;
        t.completedAt = OffsetDateTime.now();
        tasks.save(t);
        activity.save(Activity.of(t.opportunityId, Activity.TASK_DONE, t.description));
    }

    @Transactional
    public void setStatus(Opportunity o, OpportunityStatus s) {
        if (o.status == s) return;
        activity.save(Activity.of(o.id, Activity.STATUS_CHANGED, brandName(o) + ": " + o.status.label + " → " + s.label));
        o.status = s;
        o.updatedAt = OffsetDateTime.now();
        if (!s.isOpen()) closeOpenWork(o);
    }

    private Opportunity createOpportunity(Message m, Conversation conv, MessageAnalysis a) {
        Brand b = findOrCreateBrand(a.brandName(), a.contactName(), conv);
        conv.brandId = b.id;
        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.conversationId = conv.id;
        o.origin = m.direction == Direction.OUTBOUND ? Origin.PITCH : Origin.INBOUND;
        o.type = a.opportunityType();
        o.compensation = a.compensation();
        o.status = o.origin == Origin.PITCH ? OpportunityStatus.PITCHED : OpportunityStatus.NEW_LEAD;
        if (o.origin == Origin.PITCH) {
            o.pitchedAt = m.sentAt.toLocalDate();
            o.pitchPlatform = conv.platform.name();
        }
        o.createdAt = OffsetDateTime.now();
        o.updatedAt = o.createdAt;
        o = opportunities.save(o);
        activity.save(Activity.of(o.id, Activity.NEW_OPPORTUNITY,
                (o.origin == Origin.PITCH ? "Pitched " : "New opportunity: ") + b.name
                        + (a.compensation() == Compensation.UNKNOWN ? "" : " (" + a.compensation().name().toLowerCase() + ")")));
        return o;
    }

    public Brand findOrCreateBrand(String name, String contactName, Conversation conv) {
        String display = name == null || name.isBlank() ? fallbackBrandName(conv) : name;
        String key = Brand.key(display);
        Optional<Brand> existing = brands.findByNameKey(key);
        if (existing.isEmpty() && conv != null && conv.platform == Platform.EMAIL && conv.counterparty != null) {
            existing = brands.findFirstByContactEmailIgnoreCase(conv.counterparty);
        }
        Brand b = existing.orElseGet(() -> {
            Brand n = new Brand();
            n.name = display;
            n.nameKey = key;
            n.createdAt = OffsetDateTime.now();
            return n;
        });
        if ((b.contactName == null || b.contactName.isBlank()) && contactName != null) b.contactName = contactName;
        if (conv != null && conv.counterparty != null) {
            if (conv.platform == Platform.EMAIL && b.contactEmail == null) b.contactEmail = conv.counterparty;
            if (conv.platform == Platform.INSTAGRAM && b.instagram == null) b.instagram = conv.counterparty;
        }
        return brands.save(b);
    }

    private static String fallbackBrandName(Conversation conv) {
        if (conv == null || conv.counterparty == null) return "Unknown brand";
        String c = conv.counterparty;
        int at = c.indexOf('@');
        return at > 0 ? c.substring(at + 1) : c;
    }

    private static void mergeFacts(Opportunity o, MessageAnalysis a) {
        if (a.opportunityType() != OpportunityType.OTHER) o.type = a.opportunityType();
        if (a.compensation() != Compensation.UNKNOWN) o.compensation = a.compensation();
        if (a.budgetAmount() > 0) {
            o.budgetAmount = BigDecimal.valueOf(a.budgetAmount());
            o.currency = a.currency().isBlank() ? o.currency : a.currency();
        }
        if (!a.budgetText().isBlank()) o.budgetText = a.budgetText();
        if (!a.deliverables().isBlank()) o.deliverables = a.deliverables();
        if (!a.usageRights().isBlank()) o.usageRights = a.usageRights();
        if (!a.campaign().isBlank()) o.campaign = a.campaign();
        if (a.brandRelated()) o.missingInfo = String.join(", ", a.missingInfo());
        if (!a.suggestedAction().isBlank()) o.nextStep = a.suggestedAction();
    }

    private Task upsertTask(Opportunity o, TaskType type, String description, Priority priority, LocalDate due, Long msgId) {
        Task t = tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN).stream()
                .filter(x -> x.type == type).findFirst().orElseGet(() -> {
                    Task n = new Task();
                    n.opportunityId = o.id;
                    n.type = type;
                    n.status = TaskStatus.OPEN;
                    n.createdAt = OffsetDateTime.now();
                    return n;
                });
        t.description = description;
        t.priority = t.priority == null || priority.ordinal() < t.priority.ordinal() ? priority : t.priority;
        t.dueDate = t.dueDate == null || (due != null && due.isBefore(t.dueDate)) ? due : t.dueDate;
        t.sourceMessageId = msgId;
        return tasks.save(t);
    }

    private String describe(Opportunity o, TaskType type, MessageAnalysis a) {
        if (!a.suggestedAction().isBlank()) return a.suggestedAction();
        String b = brandName(o);
        return switch (type) {
            case SEND_RATES -> "Reply to " + b + " with rates";
            case SEND_MEDIA_KIT -> "Send media kit to " + b;
            case CONFIRM_AVAILABILITY -> "Confirm availability with " + b;
            case NEGOTIATE -> "Respond to " + b + " on terms";
            case COMPLETE_APPLICATION -> "Complete " + b + " creator application";
            case SIGN_CONTRACT -> "Review and sign " + b + " contract";
            case CONFIRM_PRODUCT -> "Confirm " + b + " product arrived";
            case CREATE_CONTENT -> "Create content for " + b;
            case REVISE_CONTENT -> "Revise content for " + b;
            case POST_CONTENT -> "Post " + b + " content";
            case SEND_INVOICE -> "Send invoice to " + b;
            default -> "Reply to " + b;
        };
    }

    private static LocalDate dueFor(TaskType type, MessageAnalysis a, LocalDate messageDay) {
        DeadlineType match = switch (type) {
            case SIGN_CONTRACT -> DeadlineType.CONTRACT;
            case COMPLETE_APPLICATION -> DeadlineType.APPLICATION;
            case CREATE_CONTENT, REVISE_CONTENT -> DeadlineType.CONTENT_DUE;
            case POST_CONTENT -> DeadlineType.POSTING;
            default -> null;
        };
        if (match != null) {
            Optional<LocalDate> d = a.deadlines().stream().filter(x -> x.type() == match)
                    .map(x -> LocalDate.parse(x.date())).min(LocalDate::compareTo);
            if (d.isPresent()) return d.get();
        }
        return switch (type) {
            case SIGN_CONTRACT -> messageDay.plusDays(2);
            case COMPLETE_APPLICATION, SEND_INVOICE -> messageDay.plusDays(3);
            case CREATE_CONTENT, REVISE_CONTENT, POST_CONTENT -> null;
            default -> messageDay.plusDays(1); // reply within a day
        };
    }

    private void addDeadlines(Opportunity o, MessageAnalysis a, LocalDate day) {
        for (MessageAnalysis.ExtractedDeadline d : a.deadlines()) {
            LocalDate date = LocalDate.parse(d.date());
            if (deadlines.existsByOpportunityIdAndTypeAndDueDate(o.id, d.type(), date)) continue;
            Deadline dl = new Deadline();
            dl.opportunityId = o.id;
            dl.type = d.type();
            dl.dueDate = date;
            dl.description = d.description();
            deadlines.save(dl);
            if (d.type() == DeadlineType.CONTENT_DUE && o.status.isOpen()) {
                upsertTask(o, TaskType.CREATE_CONTENT, "Create content for " + brandName(o), Priority.MEDIUM, date, null);
                if (o.status == OpportunityStatus.PRODUCT_RECEIVED || o.status == OpportunityStatus.PRODUCT_PENDING) {
                    setStatus(o, OpportunityStatus.CONTENT_TO_CREATE);
                }
            }
        }
    }

    private void supersedePendingDrafts(Opportunity o) {
        for (Draft d : drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING)) {
            d.status = DraftStatus.SUPERSEDED;
            drafts.save(d);
        }
    }

    private void closeOpenWork(Opportunity o) {
        for (Task t : tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN)) {
            t.status = TaskStatus.DISMISSED;
            t.completedAt = OffsetDateTime.now();
            tasks.save(t);
        }
        followUps.stop(o);
        supersedePendingDrafts(o);
    }

    public String brandName(Opportunity o) {
        return brands.findById(o.brandId).map(b -> b.name).orElse("Brand");
    }
}
