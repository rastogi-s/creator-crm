package com.creatorcrm.dms;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** Instagram DMs: the sorted list, tagging leads, and reply reminders for lead DMs left waiting. */
@SpringBootTest
@ActiveProfiles("test")
class DmLeadsIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired FakeLlm llm;
    @Autowired IngestionService ingestion;
    @Autowired DmInbox inbox;
    @Autowired DmReminders reminders;
    @Autowired ConversationRepo conversations;
    @Autowired OpportunityRepo opportunities;
    @Autowired TaskRepo tasks;
    @Autowired SettingsService settings;

    @BeforeEach
    void reset() {
        llm.next.clear();
        llm.failWith = null;
        settings.update(Map.of(SettingsService.DM_REPLY_REMINDER_HOURS, "6"));
    }

    @AfterEach
    void restore() {
        llm.failWith = null;
        settings.update(Map.of(SettingsService.DM_REPLY_REMINDER_HOURS, ""));
    }

    private String handle() {
        return "user_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Conversation dm(String handle, Direction dir, int hoursAgo, String text, Intent intent) {
        if (intent != null) llm.next.add(analysis(intent, intent == Intent.NOT_BRAND_RELATED ? "" : "Brand " + handle, true, List.of()));
        boolean in = dir == Direction.INBOUND;
        ingestion.store(List.of(new NormalizedMessage(Platform.INSTAGRAM, UUID.randomUUID().toString(), "ig:" + handle, dir,
                in ? handle : "me", in ? handle : "me", in ? "me" : handle, handle, "", text, "", "",
                OffsetDateTime.now().minusHours(hoursAgo), false)));
        ingestion.processPending();
        return conversations.findByPlatformAndExternalId(Platform.INSTAGRAM, "ig:" + handle).orElseThrow();
    }

    private List<Task> openTasks(Conversation c) {
        return opportunities.findFirstByConversationIdOrderByIdDesc(c.id)
                .map(o -> tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN)).orElse(List.of());
    }

    private DmInbox.Row row(Conversation c) {
        return inbox.list().stream().filter(r -> r.id().equals(c.id)).findFirst().orElseThrow();
    }

    @Test
    void taggingADmAsLeadStartsADealAndRemindsOnceWhenItHasWaited() {
        String h = handle();
        Conversation c = dm(h, Direction.INBOUND, 8, "hey, do you ever feature small shops?", Intent.NOT_BRAND_RELATED);
        DmInbox.Row before = row(c);
        assertThat(before.lead()).isFalse();
        assertThat(before.handle()).isEqualTo(h);
        assertThat(before.waitingSince()).isNotNull();
        assertThat(before.opportunityId()).isNull();

        DmInbox.Row after = inbox.tag(c.id, "LEAD");

        assertThat(after.lead()).isTrue();
        assertThat(after.opportunityId()).isNotNull();
        Opportunity o = opportunities.findById(after.opportunityId()).orElseThrow();
        assertThat(o.status).isEqualTo(OpportunityStatus.NEW_LEAD);
        List<Task> open = openTasks(c);
        assertThat(open).singleElement().satisfies(t -> {
            assertThat(t.type).isEqualTo(TaskType.REPLY);
            assertThat(t.priority).isEqualTo(Priority.HIGH);
            assertThat(t.dueDate).isEqualTo(settings.today());
            assertThat(t.description).contains("@" + h);
            assertThat(t.brief).contains("waiting for a reply");
        });
        assertThat(row(c).reminderTaskId()).isEqualTo(open.getFirst().id);

        // Dismissed: no second reminder for the same unanswered message
        Task t = open.getFirst();
        t.status = TaskStatus.DISMISSED;
        tasks.save(t);
        reminders.run();
        assertThat(openTasks(c)).isEmpty();
    }

    @Test
    void noReminderBeforeTheSetHoursOrWhenOff() {
        Conversation fresh = dm(handle(), Direction.INBOUND, 2, "hi!", Intent.NOT_BRAND_RELATED);
        inbox.tag(fresh.id, "LEAD");
        assertThat(openTasks(fresh)).isEmpty();

        settings.update(Map.of(SettingsService.DM_REPLY_REMINDER_HOURS, "0"));
        Conversation old = dm(handle(), Direction.INBOUND, 30, "hello?", Intent.NOT_BRAND_RELATED);
        inbox.tag(old.id, "LEAD");
        assertThat(openTasks(old)).isEmpty();

        settings.update(Map.of(SettingsService.DM_REPLY_REMINDER_HOURS, "1"));
        reminders.run();
        assertThat(openTasks(fresh)).singleElement().satisfies(t -> assertThat(t.type).isEqualTo(TaskType.REPLY));
    }

    @Test
    void claudesLeadKeepsItsToDoButItMovesToTodayAndHigh() {
        Conversation c = dm(handle(), Direction.INBOUND, 10, "We'd love a paid Reel", Intent.NEW_OPPORTUNITY);
        assertThat(row(c).lead()).isTrue();
        Task t = openTasks(c).getFirst();
        t.dueDate = settings.today().plusDays(1);
        t.priority = Priority.LOW;
        tasks.save(t);

        reminders.run();

        assertThat(openTasks(c)).singleElement().satisfies(x -> {
            assertThat(x.id).isEqualTo(t.id);
            assertThat(x.dueDate).isEqualTo(settings.today());
            assertThat(x.priority).isEqualTo(Priority.HIGH);
        });
    }

    @Test
    void herReplyInTheInstagramAppClosesTheReminderEvenBeforeClaudeReadsIt() {
        String h = handle();
        Conversation c = dm(h, Direction.INBOUND, 9, "collab?", Intent.NOT_BRAND_RELATED);
        inbox.tag(c.id, "LEAD");
        assertThat(openTasks(c)).hasSize(1);

        llm.failWith = new RuntimeException("Claude is down");
        dm(h, Direction.OUTBOUND, 1, "Yes! Tell me more", null);
        reminders.run();

        assertThat(openTasks(c)).isEmpty();
        DmInbox.Row r = row(c);
        assertThat(r.waitingSince()).isNull();
        assertThat(r.lastFromMe()).isTrue();

        // Claude back: her reply is read, so nothing is left waiting for the next test
        llm.failWith = null;
        llm.next.add(analysis(Intent.CREATOR_REPLY, "Brand " + h, false, List.of()));
        ingestion.processPending();
        assertThat(ingestion.status().waitingForAi()).isZero();
    }

    @Test
    void notALeadClosesAnUndecidedDealAndStopsClaudeStartingOne() {
        Conversation c = dm(handle(), Direction.INBOUND, 3, "We'd love a paid Reel", Intent.NEW_OPPORTUNITY);
        Long oppId = row(c).opportunityId();

        DmInbox.Row r = inbox.tag(c.id, "NOT_LEAD");

        assertThat(r.lead()).isFalse();
        Opportunity o = opportunities.findById(oppId).orElseThrow();
        assertThat(o.status).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(o.closedReason).isEqualTo("Not a lead");
        assertThat(openTasks(c)).isEmpty();

        // Changing her mind reopens it
        inbox.tag(c.id, "LEAD");
        assertThat(opportunities.findById(oppId).orElseThrow().status).isEqualTo(OpportunityStatus.NEW_LEAD);

        // A DM she called "not a lead" before any deal: Claude seeing a brand later doesn't start one
        String h = handle();
        Conversation other = dm(h, Direction.INBOUND, 5, "hey", Intent.NOT_BRAND_RELATED);
        inbox.tag(other.id, "NOT_LEAD");
        dm(h, Direction.INBOUND, 4, "actually we're a brand, want a collab?", Intent.NEW_OPPORTUNITY);
        assertThat(opportunities.findFirstByConversationIdOrderByIdDesc(other.id)).isEmpty();
        assertThat(row(other).claudeLead()).isTrue();
        assertThat(row(other).lead()).isFalse();
    }

    @Test
    void reminderHoursAreValidated() {
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.DM_REPLY_REMINDER_HOURS, "500")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inbox.tag(-1L, "LEAD")).isInstanceOf(IllegalArgumentException.class);
    }
}
