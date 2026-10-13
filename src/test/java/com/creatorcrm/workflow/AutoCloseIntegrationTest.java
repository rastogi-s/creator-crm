package com.creatorcrm.workflow;

import static com.creatorcrm.FakeLlm.analysis;
import static com.creatorcrm.FakeLlm.deadline;
import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.digest.DigestService;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.web.CrmController;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** To-dos and deadlines closing themselves: on her messages, the brand's, the deal moving on, and the calendar. */
@SpringBootTest
@ActiveProfiles("test")
class AutoCloseIntegrationTest {

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
    @Autowired OpportunityRepo opportunities;
    @Autowired ConversationRepo conversations;
    @Autowired TaskRepo tasks;
    @Autowired DeadlineRepo deadlines;
    @Autowired StaleWork staleWork;
    @Autowired DigestService digest;
    @Autowired CrmController crm;
    @Autowired DraftService draftService;
    @Autowired DraftRepo drafts;

    private String thread;
    private String brand;
    private final OffsetDateTime base = OffsetDateTime.now().minusDays(20);

    @BeforeEach
    void reset() {
        llm.next.clear();
        thread = "auto" + UUID.randomUUID().toString().substring(0, 6);
        brand = "Brand " + thread;
    }

    private void email(Direction dir, int day, String text, com.creatorcrm.llm.MessageAnalysis a) {
        llm.next.add(a);
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, dir,
                dir == Direction.INBOUND ? "maya@" + thread + ".test" : "me@creator.test", "Maya",
                dir == Direction.INBOUND ? "me@creator.test" : "maya@" + thread + ".test", "maya@" + thread + ".test",
                "Collab", text, "<" + UUID.randomUUID() + "@mail>", "", base.plusDays(day), false)));
        ingestion.processPending();
    }

    private Opportunity opp() {
        Long convId = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        return opportunities.findFirstByConversationIdOrderByIdDesc(convId).orElseThrow();
    }

    private Task task(TaskType type) {
        return tasks.findByOpportunityIdOrderByCreatedAtDesc(opp().id).stream().filter(t -> t.type == type).findFirst().orElseThrow();
    }

    private Deadline deadlineOf(DeadlineType type) {
        return deadlines.findByOpportunityIdOrderByDueDateAsc(opp().id).stream().filter(d -> d.type == type).findFirst().orElseThrow();
    }

    @Test
    void herReplyClosesTheReplyToDoAndTodaySaysWhy() {
        email(Direction.INBOUND, 0, "Can you send your rates?", analysis(Intent.RATES_REQUEST, brand, true, List.of()));
        email(Direction.OUTBOUND, 1, "My rate is $500", analysis(Intent.SENT_RATES_OR_MEDIA_KIT, brand, false, List.of()));

        Task t = task(TaskType.SEND_RATES);
        assertThat(t.status).isEqualTo(TaskStatus.DONE);
        assertThat(t.autoClosedReason).startsWith("You wrote back to " + brand + " on ");
        assertThat(digest.morning().closedForYou())
                .anySatisfy(c -> {
                    assertThat(c.refId()).isEqualTo(t.id);
                    assertThat(c.kind()).isEqualTo("TASK");
                    assertThat(c.reason()).isEqualTo(t.autoClosedReason);
                });
    }

    @Test
    void approvedContentClosesContentWorkAndItsDeadline() {
        String due = base.plusDays(15).toLocalDate().toString();
        email(Direction.INBOUND, 0, "Here's the brief, content due in two weeks", analysis(Intent.CONTENT_BRIEF, brand, false,
                List.of(deadline(DeadlineType.CONTENT_DUE, due))));
        assertThat(task(TaskType.CREATE_CONTENT).status).isEqualTo(TaskStatus.OPEN);

        email(Direction.INBOUND, 5, "Love it, approved! Post on Friday.", analysis(Intent.CONTENT_APPROVED, brand, false, List.of()));

        Task create = task(TaskType.CREATE_CONTENT);
        assertThat(create.status).isEqualTo(TaskStatus.DONE);
        assertThat(create.autoClosedReason).startsWith(brand + " approved your content");
        assertThat(task(TaskType.POST_CONTENT).status).isEqualTo(TaskStatus.OPEN);
        Deadline d = deadlineOf(DeadlineType.CONTENT_DUE);
        assertThat(d.done).isTrue();
        assertThat(d.doneReason).isEqualTo("The " + brand + " deal moved on to Post");
    }

    @Test
    void postingTheContentClosesThePostToDo() {
        email(Direction.INBOUND, 0, "Approved, please post", analysis(Intent.CONTENT_APPROVED, brand, false, List.of()));
        email(Direction.OUTBOUND, 2, "It's live!", analysis(Intent.CONTENT_POSTED, brand, false, List.of()));
        Task post = task(TaskType.POST_CONTENT);
        assertThat(post.status).isEqualTo(TaskStatus.DONE);
        assertThat(post.autoClosedReason).startsWith("You told " + brand + " the post is live");
    }

    @Test
    void brandSayingNoDismissesWithTheReason() {
        email(Direction.INBOUND, 0, "Can you send your rates?", analysis(Intent.RATES_REQUEST, brand, true, List.of()));
        email(Direction.INBOUND, 1, "We've decided to go another way.", analysis(Intent.DECLINE, brand, false, List.of()));
        Task t = task(TaskType.SEND_RATES);
        assertThat(t.status).isEqualTo(TaskStatus.DISMISSED);
        assertThat(t.autoClosedReason).isEqualTo(brand + " said no to this one");
    }

    @Test
    void passedApplicationDateRetiresTheApplicationAndReopenSticks() {
        String closes = base.plusDays(3).toLocalDate().toString();
        email(Direction.INBOUND, 0, "Apply to our program by the deadline", analysis(Intent.APPLICATION_FORM, brand, false,
                List.of(deadline(DeadlineType.APPLICATION, closes))));
        assertThat(task(TaskType.COMPLETE_APPLICATION).status).isEqualTo(TaskStatus.OPEN);

        staleWork.sweep();

        Task t = task(TaskType.COMPLETE_APPLICATION);
        assertThat(t.status).isEqualTo(TaskStatus.DISMISSED);
        assertThat(t.autoClosedReason).startsWith("The application closed on ");
        Deadline d = deadlineOf(DeadlineType.APPLICATION);
        assertThat(d.done).isTrue();
        assertThat(d.doneReason).startsWith("The application closed on ");

        // She knows better: reopened, it stays open through the next sweep.
        crm.reopenTask(t.id);
        crm.reopenDeadline(d.id);
        staleWork.sweep();
        assertThat(task(TaskType.COMPLETE_APPLICATION).status).isEqualTo(TaskStatus.OPEN);
        assertThat(task(TaskType.COMPLETE_APPLICATION).autoClosedReason).isNull();
        assertThat(deadlineOf(DeadlineType.APPLICATION).done).isFalse();
    }

    @Test
    void aLeadQuietForAMonthIsRetired() {
        email(Direction.INBOUND, -15, "Interested in a collab?", analysis(Intent.NEW_OPPORTUNITY, brand, true, List.of()));
        Task t = task(TaskType.REPLY);
        t.createdAt = base.minusDays(15);
        tasks.save(t);

        staleWork.sweep();

        t = task(TaskType.REPLY);
        assertThat(t.status).isEqualTo(TaskStatus.DISMISSED);
        assertThat(t.autoClosedReason).startsWith("Nothing from " + brand + " since ").endsWith("so this lead has gone quiet");
    }

    @Test
    void aRecentLeadStaysOpen() {
        email(Direction.INBOUND, 19, "Interested in a collab?", analysis(Intent.NEW_OPPORTUNITY, brand, true, List.of()));
        staleWork.sweep();
        assertThat(task(TaskType.REPLY).status).isEqualTo(TaskStatus.OPEN);
    }

    @Test
    void workLeftOnAColdDealIsDismissed() {
        email(Direction.INBOUND, 0, "Can you send your rates?", analysis(Intent.RATES_REQUEST, brand, true, List.of()));
        Opportunity o = opp();
        o.status = OpportunityStatus.COLD; // as the follow-up engine marks it, without closing its to-dos
        o.closedReason = "No reply after 5 follow-ups";
        opportunities.save(o);

        staleWork.sweep();

        Task t = task(TaskType.SEND_RATES);
        assertThat(t.status).isEqualTo(TaskStatus.DISMISSED);
        assertThat(t.autoClosedReason).isEqualTo("The " + brand + " deal went cold: no reply after 5 follow-ups");
    }

    @Test
    void tellClaudeWritesTheEmailHerWayAndSendingItClosesTheToDo() {
        email(Direction.INBOUND, 19, "Apply to our creator program", analysis(Intent.APPLICATION_FORM, brand, false, List.of()));
        Task t = task(TaskType.COMPLETE_APPLICATION);

        Draft first = crm.askAboutTask(t.id, new CrmController.TaskAsk("Tell them I applied yesterday"));
        assertThat(first.taskId).isEqualTo(t.id);
        assertThat(llm.lastDraftInput.extraInstructions()).contains("Tell them I applied yesterday");

        // Changing her mind replaces the draft rather than piling up a second one.
        Draft second = crm.askAboutTask(t.id, new CrmController.TaskAsk("Ask if they need my media kit too"));
        assertThat(drafts.findById(first.id).orElseThrow().status).isEqualTo(DraftStatus.SUPERSEDED);
        assertThat(llm.lastDraftInput.extraInstructions()).contains("media kit");

        draftService.markSentManually(second.id);
        Task done = task(TaskType.COMPLETE_APPLICATION);
        assertThat(done.status).isEqualTo(TaskStatus.DONE);
        assertThat(done.autoClosedReason).startsWith("You sent the email for it on ");
    }
}
