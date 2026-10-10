package com.creatorcrm.dms;

import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reply reminders for Instagram DMs: when a lead's DM has waited longer than the hours set in Settings, she gets a
 * "Reply to @name" to-do for today (or the to-do she already has moves to today, marked high). One reminder per
 * unanswered stretch: if she dismisses it, it stays dismissed until they write again after her next reply.
 */
@Service
public class DmReminders {
    private static final Logger log = LoggerFactory.getLogger(DmReminders.class);
    /** Older than this, an unanswered DM is history (an import), not something to remind her about. */
    static final Duration TOO_OLD = Duration.ofDays(30);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final OpportunityRepo opportunities;
    private final TaskRepo tasks;
    private final WorkflowEngine workflow;
    private final SettingsService settings;
    private final DraftService drafts;
    private final LlmClient llm;

    public DmReminders(ConversationRepo conversations, MessageRepo messages, OpportunityRepo opportunities,
                       TaskRepo tasks, WorkflowEngine workflow, SettingsService settings, DraftService drafts,
                       LlmClient llm) {
        this.conversations = conversations;
        this.messages = messages;
        this.opportunities = opportunities;
        this.tasks = tasks;
        this.workflow = workflow;
        this.settings = settings;
        this.drafts = drafts;
        this.llm = llm;
    }

    /** Checks every lead DM; returns how many reminders were made. Runs after each sync. */
    public int run() {
        int made = 0;
        for (Conversation c : conversations.findByPlatform(Platform.INSTAGRAM)) {
            try {
                if (check(c)) made++;
            } catch (RuntimeException e) {
                log.warn("Could not check DM {} for a reply reminder: {}", c.id, e.getMessage());
            }
        }
        if (made > 0) log.info("DM reply reminders: {}", made);
        return made;
    }

    /** True when a reminder was made (or an existing to-do moved to today) for this conversation. */
    @Transactional
    public boolean check(Conversation c) {
        if (!c.isLead()) return false;
        List<Message> msgs = messages.findByConversationIdOrderBySentAtAsc(c.id);
        if (msgs.isEmpty()) return false;
        Message last = msgs.get(msgs.size() - 1);
        Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(c.id).orElse(null);
        if (last.direction == Direction.OUTBOUND) {
            if (o != null) closeAnswered(o, msgs, last.sentAt);
            return false;
        }
        int hours = settings.dmReplyReminderHours();
        if (hours <= 0) return false;
        Message first = DmInbox.unanswered(msgs).get(0);
        OffsetDateTime now = OffsetDateTime.now();
        if (first.id.equals(c.remindedMessageId) || first.sentAt.isAfter(now.minusHours(hours))
                || first.sentAt.isBefore(now.minus(TOO_OLD))) return false;

        if (o == null) o = workflow.startLead(c, DmInbox.handle(c, msgs));
        c.remindedMessageId = first.id;
        conversations.save(c);
        if (!o.status.isOpen()) return false;

        String handle = DmInbox.handle(c, msgs);
        LocalDate today = settings.today();
        Task existing = tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN).stream()
                .filter(t -> TaskType.ANSWERED_BY_OUTBOUND.contains(t.type)).findFirst().orElse(null);
        if (existing != null) {
            existing.priority = Priority.HIGH;
            if (existing.dueDate == null || existing.dueDate.isAfter(today)) existing.dueDate = today;
            tasks.save(existing);
            return true;
        }
        Task t = workflow.upsertTask(o, TaskType.REPLY, "Reply to @" + handle + "'s Instagram DM", Priority.HIGH, today, last.id);
        t.brief = brief(handle, first.sentAt, last.sentAt, now);
        tasks.save(t);
        if (llm.isConfigured()) {
            try {
                drafts.draftForTask(t);
            } catch (RuntimeException e) {
                log.warn("Could not draft a DM reply for task {}: {}", t.id, e.getMessage());
            }
        }
        return true;
    }

    /** She answered in the Instagram app: reply to-dos for messages before her answer are done. */
    private void closeAnswered(Opportunity o, List<Message> msgs, OffsetDateTime answeredAt) {
        Map<Long, Message> byId = msgs.stream().collect(Collectors.toMap(m -> m.id, Function.identity()));
        for (Task t : tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN)) {
            if (!TaskType.ANSWERED_BY_OUTBOUND.contains(t.type) || t.sourceMessageId == null) continue;
            Message from = byId.get(t.sourceMessageId);
            if (from != null && from.sentAt.isBefore(answeredAt)) workflow.completeTask(t);
        }
    }

    private String brief(String handle, OffsetDateTime waitingSince, OffsetDateTime lastAt, OffsetDateTime now) {
        long hours = Duration.between(waitingSince, now).toHours();
        String waited = hours >= 48 ? (hours / 24) + " days" : hours + (hours == 1 ? " hour" : " hours");
        OffsetDateTime windowEnds = lastAt.plusHours(DmInbox.REPLY_WINDOW_HOURS);
        String window = windowEnds.isAfter(now)
                ? "You can send a reply from the app until " + WHEN.format(windowEnds.atZoneSameInstant(settings.zone())) + "; after that, reply in the Instagram app."
                : "It's been over 24 hours, so Instagram only lets you answer from the Instagram app now.";
        return "@" + handle + " messaged you on Instagram " + waited + " ago and is waiting for a reply. " + window;
    }
}
