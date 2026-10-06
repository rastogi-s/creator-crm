package com.creatorcrm.progress;

import static com.creatorcrm.FakeLlm.analysis;
import static com.creatorcrm.FakeLlm.atStage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DealStage;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.MessageAnalysis;
import com.creatorcrm.llm.StageSeen;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.StageEntryRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.workflow.WorkflowEngine;
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

/** The progress diagram: emails put a deal at its stage, even skipping steps; the button and invoices finish it. */
@SpringBootTest
@ActiveProfiles("test")
class DealProgressIntegrationTest {

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
    @Autowired MessageRepo messages;
    @Autowired TaskRepo tasks;
    @Autowired StageEntryRepo stages;
    @Autowired WorkflowEngine workflow;
    @Autowired DealProgress progress;

    private String thread;
    private final OffsetDateTime base = OffsetDateTime.now().minusDays(20);

    @BeforeEach
    void reset() {
        llm.next.clear();
        for (Message m : messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc()) {
            m.filteredReason = "left over from another test";
            messages.save(m);
        }
        thread = "stage" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void email(Direction dir, int day, MessageAnalysis a) {
        llm.next.add(a);
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, dir,
                dir == Direction.INBOUND ? "maya@" + thread + ".test" : "me@creator.test", "Maya",
                dir == Direction.INBOUND ? "me@creator.test" : "maya@" + thread + ".test", "maya@" + thread + ".test",
                "Collab", "Hello", "<" + UUID.randomUUID() + "@mail>", "", base.plusDays(day), false)));
        ingestion.processPending();
    }

    private MessageAnalysis says(Intent intent, StageSeen stage) {
        return atStage(analysis(intent, "Brand " + thread, false, List.of()), stage);
    }

    private MessageAnalysis gifted(Intent intent, StageSeen stage) {
        MessageAnalysis a = says(intent, stage);
        return new MessageAnalysis(true, a.brandName(), "Maya", intent, a.opportunityType(), Compensation.GIFTED, 0, "",
                "", "1 post", "", "", List.of(), List.of(), false, a.urgency(), "", "", "", List.of(), stage);
    }

    private Opportunity opp() {
        Long conv = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        return opportunities.findFirstByConversationIdOrderByIdDesc(conv).orElseThrow();
    }

    private static List<String> states(DealProgress.View v) {
        return v.steps().stream().map(s -> s.name() + ":" + s.state()).toList();
    }

    @Test
    void anEmailPutsTheDealAtItsStageEvenWhenStepsWereSkipped() {
        email(Direction.INBOUND, 0, says(Intent.NEW_OPPORTUNITY, StageSeen.CREATE_CONTENT));
        assertThat(opp().stage).as("a first outreach is never a locked deal").isNull();
        assertThat(progress.view(opp().id).shown()).isFalse();

        email(Direction.INBOUND, 2, says(Intent.GENERAL_REPLY, StageSeen.POST)); // "Loved the draft, post it Thursday"
        Opportunity o = opp();
        assertThat(o.stage).isEqualTo(DealStage.POST);
        assertThat(o.status).isEqualTo(OpportunityStatus.SCHEDULED_TO_POST);
        assertThat(stages.findByOpportunityIdOrderByReachedAtAscIdAsc(o.id).getLast().reachedAt.toLocalDate())
                .as("dated by the email, not by when it was read").isEqualTo(base.plusDays(2).toLocalDate());

        DealProgress.View v = progress.view(o.id);
        assertThat(v.state()).isEqualTo("active");
        assertThat(states(v)).containsExactly("Agreed:done", "Contract:done", "Create content:done", "Brand approval:done",
                "Post:now", "Invoice:todo", "Paid:todo");
        assertThat(v.step()).isEqualTo(5);
        assertThat(v.headline()).isEqualTo("Now: post it");
        assertThat(v.next()).isEqualTo("INVOICE");

        email(Direction.INBOUND, 3, says(Intent.GENERAL_REPLY, StageSeen.CREATE_CONTENT));
        assertThat(opp().stage).as("Claude's read never moves a deal back").isEqualTo(DealStage.POST);
    }

    @Test
    void movingPastAStageFinishesItsToDos() {
        email(Direction.INBOUND, 0, says(Intent.CONTRACT_SENT, StageSeen.CONTRACT));
        Opportunity o = opp();
        assertThat(o.stage).isEqualTo(DealStage.CONTRACT);
        assertThat(tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN)).extracting(t -> t.type).contains(TaskType.SIGN_CONTRACT);

        email(Direction.OUTBOUND, 1, says(Intent.CREATOR_REPLY, StageSeen.BRAND_APPROVAL)); // "Here's my draft!"
        o = opp();
        assertThat(o.stage).isEqualTo(DealStage.BRAND_APPROVAL);
        assertThat(o.status).isEqualTo(OpportunityStatus.AWAITING_APPROVAL);
        assertThat(tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN)).extracting(t -> t.type).doesNotContain(TaskType.SIGN_CONTRACT);
    }

    @Test
    void aPaidDealIsOnlyDoneWhenSheSaysTheMoneyArrived() {
        email(Direction.INBOUND, 0, says(Intent.CONTENT_APPROVED, StageSeen.POST));
        email(Direction.OUTBOUND, 1, says(Intent.CONTENT_POSTED, StageSeen.INVOICE));
        Opportunity o = opp();
        assertThat(o.stage).isEqualTo(DealStage.INVOICE);
        assertThat(o.status).isEqualTo(OpportunityStatus.PAYMENT_PENDING);

        email(Direction.INBOUND, 2, says(Intent.PAYMENT_UPDATE, StageSeen.DONE)); // "Payment has been sent"
        o = opp();
        assertThat(o.stage).isEqualTo(DealStage.PAYMENT);
        assertThat(o.status).isEqualTo(OpportunityStatus.PAYMENT_PENDING);

        assertThatThrownBy(() -> progress.advance(opp().id, "INVOICE")).isInstanceOf(IllegalStateException.class);
        DealProgress.View v = progress.advance(opp().id, "DONE");
        assertThat(v.state()).isEqualTo("finished");
        assertThat(v.action()).isNull();
        o = opp();
        assertThat(o.status).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(o.closedReason).isEqualTo("Paid");
        assertThat(o.stage).isEqualTo(DealStage.DONE);
    }

    @Test
    void aGiftedCollabWrapsUpWhenPosted() {
        email(Direction.INBOUND, 0, gifted(Intent.PRODUCT_SHIPPED, StageSeen.PRODUCT));
        DealProgress.View v = progress.view(opp().id);
        assertThat(states(v)).containsExactly("Agreed:done", "Product:now", "Create content:todo", "Brand approval:todo",
                "Post:todo", "Wrapped up:todo");

        progress.advance(opp().id, "CREATE_CONTENT");
        assertThat(opp().status).isEqualTo(OpportunityStatus.CONTENT_TO_CREATE);
        email(Direction.OUTBOUND, 3, gifted(Intent.CONTENT_POSTED, StageSeen.DONE));
        v = progress.view(opp().id);
        assertThat(opp().status).isEqualTo(OpportunityStatus.POSTED);
        assertThat(v.state()).isEqualTo("finished");
        assertThat(v.steps()).allMatch(s -> s.state().equals("done"));
    }

    @Test
    void aDealThatGoesColdStopsWhereItWas() {
        email(Direction.INBOUND, 0, says(Intent.CONTENT_BRIEF, StageSeen.CREATE_CONTENT));
        Opportunity o = opp();
        workflow.setStatus(o, OpportunityStatus.COLD);
        opportunities.save(o);
        DealProgress.View v = progress.view(o.id);
        assertThat(v.state()).isEqualTo("paused");
        assertThat(v.headline()).isEqualTo("Stopped at create content");
        assertThat(v.action()).isNull();

        email(Direction.INBOUND, 1, says(Intent.GENERAL_REPLY, StageSeen.POST));
        assertThat(opp().stage).as("a closed or cold deal isn't moved by emails").isEqualTo(DealStage.CREATE_CONTENT);
    }
}
