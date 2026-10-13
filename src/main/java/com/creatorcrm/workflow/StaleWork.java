package com.creatorcrm.workflow;

import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Enums.DealStage;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.StageEntry;
import com.creatorcrm.domain.Task;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.StageEntryRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retires to-dos and deadlines that time has made pointless, after every sync: an application whose closing date
 * passed, a reply to a lead nobody has written in for a month, work from a step the deal already moved past, anything
 * left open on a deal that closed or went cold. Messages close their own to-dos as they come in ({@link WorkflowEngine});
 * this catches what only the calendar changes. One she reopened is left alone.
 */
@Service
public class StaleWork {
    /** A lead nobody has written in for this long has gone quiet; replying now would be odd. */
    static final int QUIET_DAYS = 30;
    /** Dates that aren't hers to meet (a launch, the brand's approval) stop being watched a week after they pass. */
    static final int PASSED_DAYS = 7;
    private static final Set<DeadlineType> FYI_DATES = Set.of(DeadlineType.LAUNCH, DeadlineType.APPROVAL, DeadlineType.OTHER);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private final TaskRepo tasks;
    private final DeadlineRepo deadlines;
    private final OpportunityRepo opportunities;
    private final MessageRepo messages;
    private final StageEntryRepo stages;
    private final WorkflowEngine workflow;
    private final SettingsService settings;

    public StaleWork(TaskRepo tasks, DeadlineRepo deadlines, OpportunityRepo opportunities, MessageRepo messages,
                     StageEntryRepo stages, WorkflowEngine workflow, SettingsService settings) {
        this.tasks = tasks;
        this.deadlines = deadlines;
        this.opportunities = opportunities;
        this.messages = messages;
        this.stages = stages;
        this.workflow = workflow;
        this.settings = settings;
    }

    /** Closes what is stale today; returns how many to-dos and deadlines it closed. */
    @Transactional
    public int sweep() {
        LocalDate today = settings.today();
        int n = 0;
        for (Task t : tasks.findByStatus(TaskStatus.OPEN)) {
            if (t.keptOpen || t.opportunityId == null) continue;
            Opportunity o = opportunities.findById(t.opportunityId).orElse(null);
            if (o == null) continue;
            String why = why(t, o, today);
            if (why == null) continue;
            boolean happened = WorkflowEngine.stageOf(t.type) != null && o.status.isOpen();
            workflow.autoClose(t, happened ? TaskStatus.DONE : TaskStatus.DISMISSED, why);
            n++;
        }
        for (Deadline d : deadlines.findByDoneFalseAndDueDateLessThanEqualOrderByDueDateAsc(today)) {
            if (d.keptOpen) continue;
            Opportunity o = opportunities.findById(d.opportunityId).orElse(null);
            if (o == null || !o.status.isOpen()) continue; // hidden everywhere already
            String why = why(d, o, today);
            if (why == null) continue;
            workflow.autoClear(d, why);
            n++;
        }
        return n;
    }

    private String why(Task t, Opportunity o, LocalDate today) {
        if (!o.status.isOpen()) return workflow.closedWhy(o);
        DealStage of = WorkflowEngine.stageOf(t.type);
        if (of != null && o.stage != null && o.stage.isAfter(of)) {
            // Only work from before the deal got here: a contract she uploads for review later still waits.
            Optional<OffsetDateTime> reached = reachedCurrentStage(o);
            if (reached.isEmpty() || t.createdAt == null || t.createdAt.isBefore(reached.get())) {
                return "The " + workflow.brandName(o) + " deal moved on to " + o.stage.label;
            }
        }
        if (t.type == TaskType.COMPLETE_APPLICATION) {
            Optional<LocalDate> closes = deadlines.findByOpportunityIdOrderByDueDateAsc(o.id).stream()
                    .filter(d -> d.type == DeadlineType.APPLICATION).map(d -> d.dueDate).max(LocalDate::compareTo);
            if (closes.isPresent() && closes.get().isBefore(today)) return "The application closed on " + DAY.format(closes.get());
        }
        if (TaskType.ANSWERED_BY_OUTBOUND.contains(t.type) && o.stage == null) {
            OffsetDateTime last = lastMessageAt(o).orElse(t.createdAt);
            if (t.createdAt != null && (last == null || t.createdAt.isAfter(last))) last = t.createdAt;
            if (last != null && last.toLocalDate().isBefore(today.minusDays(QUIET_DAYS))) {
                return "Nothing from " + workflow.brandName(o) + " since " + DAY.format(last.toLocalDate())
                        + ", so this lead has gone quiet";
            }
        }
        return null;
    }

    private String why(Deadline d, Opportunity o, LocalDate today) {
        if (d.type == DeadlineType.APPLICATION && d.dueDate.isBefore(today)) {
            return "The application closed on " + DAY.format(d.dueDate);
        }
        DealStage of = WorkflowEngine.stageOf(d.type);
        if (of != null && o.stage != null && o.stage.isAfter(of)) {
            return "The " + workflow.brandName(o) + " deal moved on to " + o.stage.label;
        }
        if (FYI_DATES.contains(d.type) && d.dueDate.isBefore(today.minusDays(PASSED_DAYS))) {
            return DAY.format(d.dueDate) + " passed over a week ago";
        }
        return null;
    }

    private Optional<OffsetDateTime> reachedCurrentStage(Opportunity o) {
        List<StageEntry> entries = stages.findByOpportunityIdOrderByReachedAtAscIdAsc(o.id);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).stage == o.stage) return Optional.of(entries.get(i).reachedAt);
        }
        return Optional.empty();
    }

    private Optional<OffsetDateTime> lastMessageAt(Opportunity o) {
        if (o.conversationId == null) return Optional.empty();
        List<Message> msgs = messages.findByConversationIdOrderBySentAtAsc(o.conversationId);
        return msgs.isEmpty() ? Optional.empty() : Optional.of(msgs.get(msgs.size() - 1).sentAt);
    }
}
