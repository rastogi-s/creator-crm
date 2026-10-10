package com.creatorcrm.dms;

import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Her Instagram DMs as one list: who wrote, whether she has answered, and whether it's a lead. She can tag a DM
 * as a lead (it gets a deal and reply reminders) or as not a lead (Claude's read is overruled).
 */
@Service
public class DmInbox {
    /** Meta lets the app send a reply only this long after the person's last message. */
    public static final long REPLY_WINDOW_HOURS = 24;

    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final OpportunityRepo opportunities;
    private final TaskRepo tasks;
    private final BrandRepo brands;
    private final WorkflowEngine workflow;
    private final DmReminders reminders;

    public DmInbox(ConversationRepo conversations, MessageRepo messages, OpportunityRepo opportunities, TaskRepo tasks,
                   BrandRepo brands, WorkflowEngine workflow, DmReminders reminders) {
        this.conversations = conversations;
        this.messages = messages;
        this.opportunities = opportunities;
        this.tasks = tasks;
        this.brands = brands;
        this.workflow = workflow;
        this.reminders = reminders;
    }

    /**
     * One DM conversation. {@code waitingSince}: her oldest message she hasn't answered (null when she has the last
     * word). {@code tag}: her own call, null when Claude's read ({@code claudeLead}) decides {@code lead}.
     */
    public record Row(Long id, String handle, String lastText, OffsetDateTime lastAt, boolean lastFromMe,
                      OffsetDateTime waitingSince, int unanswered, OffsetDateTime replyWindowEnds, String tag,
                      Boolean claudeLead, boolean lead, Long opportunityId, String brand, String status,
                      Long reminderTaskId, LocalDate reminderDue, int messageCount) {}

    public record Line(Long id, boolean fromMe, String text, OffsetDateTime sentAt) {}

    public record Detail(Row dm, List<Line> messages) {}

    @Transactional(readOnly = true)
    public List<Row> list() {
        List<Conversation> convs = conversations.findByPlatform(Platform.INSTAGRAM);
        if (convs.isEmpty()) return List.of();
        Map<Long, List<Message>> byConv = messages.findByConversationIdInOrderBySentAtAsc(
                        convs.stream().map(c -> c.id).toList()).stream()
                .collect(Collectors.groupingBy(m -> m.conversationId));
        List<Row> out = new ArrayList<>();
        for (Conversation c : convs) {
            List<Message> msgs = byConv.getOrDefault(c.id, List.of());
            if (!msgs.isEmpty()) out.add(row(c, msgs));
        }
        out.sort(Comparator.comparing(Row::lastAt).reversed());
        return out;
    }

    @Transactional(readOnly = true)
    public Detail detail(Long id) {
        Conversation c = dm(id);
        List<Message> msgs = messages.findByConversationIdOrderBySentAtAsc(c.id);
        return new Detail(row(c, msgs), msgs.stream()
                .map(m -> new Line(m.id, m.direction == Direction.OUTBOUND, m.content, m.sentAt)).toList());
    }

    /** {@code tag}: LEAD, NOT_LEAD, or blank to go back to Claude's read. */
    @Transactional
    public Row tag(Long id, String tag) {
        Conversation c = dm(id);
        String t = tag == null || tag.isBlank() ? null : tag.trim().toUpperCase();
        if (t != null && !t.equals(Conversation.LEAD) && !t.equals(Conversation.NOT_LEAD)) {
            throw new IllegalArgumentException("Tag must be LEAD or NOT_LEAD");
        }
        c.leadTag = t;
        conversations.save(c);
        List<Message> msgs = messages.findByConversationIdOrderBySentAtAsc(c.id);
        Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(c.id).orElse(null);
        if (Conversation.LEAD.equals(t)) {
            if (o == null) {
                workflow.startLead(c, handle(c, msgs));
            } else if (o.status == OpportunityStatus.CLOSED && NOT_A_LEAD.equals(o.closedReason)) {
                o.closedReason = null;
                workflow.setStatus(o, OpportunityStatus.NEW_LEAD);
                opportunities.save(o);
            }
            c.remindedMessageId = null; // she may have dismissed one when it was "not a lead"
            conversations.save(c);
            reminders.check(c);
        } else if (Conversation.NOT_LEAD.equals(t) && o != null && o.status.isOpen() && o.stage == null) {
            // Only a deal still being decided: one the brand already said yes to stays, in case she tagged by mistake.
            o.closedReason = NOT_A_LEAD;
            workflow.setStatus(o, OpportunityStatus.CLOSED);
            opportunities.save(o);
        }
        return row(c, msgs);
    }

    static final String NOT_A_LEAD = "Not a lead";

    private Conversation dm(Long id) {
        Conversation c = conversations.findById(id).orElseThrow(() -> new IllegalArgumentException("No such DM"));
        if (c.platform != Platform.INSTAGRAM) throw new IllegalArgumentException("Not an Instagram DM");
        return c;
    }

    /** Their Instagram username, from their latest message; the account id when they never wrote. */
    static String handle(Conversation c, List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.direction != Direction.INBOUND) continue;
            if (m.senderName != null && !m.senderName.isBlank()) return m.senderName;
            if (m.sender != null && !m.sender.isBlank()) return m.sender;
        }
        for (Message m : msgs) {
            if (m.direction == Direction.OUTBOUND && m.recipient != null && !m.recipient.isBlank()) return m.recipient;
        }
        return c.counterparty == null ? "someone" : c.counterparty;
    }

    /** Her messages she hasn't answered yet: everything they sent after her last message. */
    static List<Message> unanswered(List<Message> msgs) {
        List<Message> out = new ArrayList<>();
        for (Message m : msgs) {
            if (m.direction == Direction.OUTBOUND) out.clear();
            else out.add(m);
        }
        return out;
    }

    private Row row(Conversation c, List<Message> msgs) {
        Message last = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);
        List<Message> waiting = unanswered(msgs);
        OffsetDateTime lastInbound = msgs.stream().filter(m -> m.direction == Direction.INBOUND)
                .map(m -> m.sentAt).max(Comparator.naturalOrder()).orElse(null);
        Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(c.id).orElse(null);
        Task reminder = o == null ? null : tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN).stream()
                .filter(t -> TaskType.ANSWERED_BY_OUTBOUND.contains(t.type)).findFirst().orElse(null);
        return new Row(c.id, handle(c, msgs), last == null ? "" : snippet(last.content),
                last == null ? c.lastMessageAt : last.sentAt, last != null && last.direction == Direction.OUTBOUND,
                waiting.isEmpty() ? null : waiting.get(0).sentAt, waiting.size(),
                lastInbound == null ? null : lastInbound.plusHours(REPLY_WINDOW_HOURS), c.leadTag, c.brandRelated, c.isLead(),
                o == null ? null : o.id, o == null ? null : brands.findById(o.brandId).map(b -> b.name).orElse(null),
                o == null ? null : o.status.label,
                reminder == null ? null : reminder.id, reminder == null ? null : reminder.dueDate, msgs.size());
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() > 160 ? t.substring(0, 157) + "…" : t;
    }
}
