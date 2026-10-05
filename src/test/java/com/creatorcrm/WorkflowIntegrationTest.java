package com.creatorcrm;

import static com.creatorcrm.FakeLlm.analysis;
import static com.creatorcrm.FakeLlm.deadline;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.digest.DigestService;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.workflow.FollowUpEngine;
import com.creatorcrm.workflow.OutreachService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.LocalDate;
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

/** End-to-end through ingestion -> (fake) AI -> workflow rules -> follow-ups -> drafts -> digest. */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired FakeLlm llm;
    @Autowired LlmClient llmClient;
    @Autowired IngestionService ingestion;
    @Autowired OpportunityRepo opportunities;
    @Autowired ConversationRepo conversations;
    @Autowired MessageRepo messages;
    @Autowired TaskRepo tasks;
    @Autowired FollowUpRepo followUps;
    @Autowired DraftRepo drafts;
    @Autowired FollowUpEngine followUpEngine;
    @Autowired WorkflowEngine workflow;
    @Autowired OutreachService outreach;
    @Autowired DigestService digest;

    @BeforeEach
    void reset() {
        llm.next.clear();
        llm.draftCalls = 0;
    }

    private String thread;
    private final OffsetDateTime base = OffsetDateTime.now().minusDays(30);

    private void email(Direction dir, int day, String text, com.creatorcrm.llm.MessageAnalysis a) {
        if (a != null) llm.next.add(a);
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

    private List<Task> open(Opportunity o) {
        return tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN);
    }

    @Test
    void applicationFormTaskCarriesBriefLinksAndSourceEmail() {
        thread = "bloom" + UUID.randomUUID().toString().substring(0, 6);
        String brand = "Bloom " + thread;
        com.creatorcrm.llm.MessageAnalysis base = analysis(Intent.APPLICATION_FORM, brand, true, List.of());
        com.creatorcrm.llm.MessageAnalysis a = new com.creatorcrm.llm.MessageAnalysis(base.brandRelated(), base.brandName(),
                base.contactName(), base.intent(), base.opportunityType(), base.compensation(), base.budgetAmount(),
                base.currency(), base.budgetText(), base.deliverables(), base.usageRights(), base.campaign(),
                base.deadlines(), base.missingInfo(), base.requiresReply(), base.urgency(), base.suggestedAction(),
                base.updatedSummary(),
                "Bloom's creator program pays $500 per UGC video. The form asks for your handles and audience stats.",
                List.of(new com.creatorcrm.llm.MessageAnalysis.TaskLink("Application form", "https://forms.bloom.test/apply?ref=ava"),
                        new com.creatorcrm.llm.MessageAnalysis.TaskLink("Made up", "https://evil.test/login")));

        email(Direction.INBOUND, 0, "Join our creator program! Apply here: https://forms.bloom.test/apply?ref=ava", a);

        Task t = open(opp()).stream().filter(x -> x.type == TaskType.COMPLETE_APPLICATION).findFirst().orElseThrow();
        assertThat(t.brief).contains("creator program");
        // Only links that really are in the email reach the to-do.
        assertThat(t.getLinks()).containsExactly(new Task.Link("Application form", "https://forms.bloom.test/apply?ref=ava"));
        assertThat(messages.findById(t.sourceMessageId).orElseThrow().content).contains("Apply here");

        DigestService.Item item = digest.morning().urgent().stream()
                .filter(i -> t.id.equals(i.refId()) && "TASK".equals(i.kind())).findFirst()
                .or(() -> digest.morning().upcoming().stream().filter(i -> t.id.equals(i.refId()) && "TASK".equals(i.kind())).findFirst())
                .orElseThrow();
        assertThat(item.task().brief()).isEqualTo(t.brief);
        assertThat(item.task().links()).hasSize(1);
        assertThat(item.task().messageId()).isEqualTo(t.sourceMessageId);
    }

    @Test
    void linkCheckIgnoresHarmlessSpellingDifferences() {
        String email = WorkflowEngine.sameUrl("Apply here (https://forms.bloom.test/apply/?a=1&amp;b=2). Thanks");
        assertThat(email).contains(WorkflowEngine.sameUrl("https://forms.bloom.test/apply/?a=1&b=2"));
        assertThat(WorkflowEngine.sameUrl("See https://bloom.test/apply/.")).contains(WorkflowEngine.sameUrl("https://bloom.test/apply"));
        assertThat(email).doesNotContain(WorkflowEngine.sameUrl("https://evil.test/apply"));
    }

    @Test
    void inboundDealLifecycle() {
        thread = "glow" + UUID.randomUUID().toString().substring(0, 6);
        String brand = "Glow " + thread;

        // Brand asks for rates -> reply task + AI draft queued for approval
        email(Direction.INBOUND, 0, "Can you send your UGC rates?", analysis(Intent.RATES_REQUEST, brand, true, List.of()));
        Opportunity o = opp();
        assertThat(o.status).isEqualTo(OpportunityStatus.AWAITING_MY_REPLY);
        assertThat(open(o)).extracting(t -> t.type).containsExactly(TaskType.SEND_RATES);
        assertThat(drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING)).hasSize(1);
        assertThat(llm.draftCalls).isEqualTo(1);

        // Creator replies from Gmail -> task done, draft superseded, follow-up #1 in 4 days
        email(Direction.OUTBOUND, 1, "My rate is ...", analysis(Intent.SENT_RATES_OR_MEDIA_KIT, brand, false, List.of()));
        o = opp();
        assertThat(o.status).isEqualTo(OpportunityStatus.NEGOTIATING);
        assertThat(open(o)).isEmpty();
        assertThat(drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING)).isEmpty();
        FollowUp fu1 = followUpEngine.scheduled(o.id).orElseThrow();
        assertThat(fu1.number).isEqualTo(1);
        assertThat(fu1.scheduledDate).isEqualTo(base.plusDays(1).toLocalDate().plusDays(4));

        // Brand silent; creator follows up on day 5 -> #1 done, #2 in 5 days
        email(Direction.OUTBOUND, 5, "Just following up!", analysis(Intent.CREATOR_FOLLOW_UP, brand, false, List.of()));
        FollowUp fu2 = followUpEngine.scheduled(o.id).orElseThrow();
        assertThat(fu2.number).isEqualTo(2);
        assertThat(fu2.scheduledDate).isEqualTo(base.plusDays(5).toLocalDate().plusDays(5));

        // Brand sends the contract -> follow-ups stop, status + signing task
        email(Direction.INBOUND, 7, "Contract attached via DocuSign", analysis(Intent.CONTRACT_SENT, brand, true,
                List.of(deadline(DeadlineType.CONTRACT, base.plusDays(9).toLocalDate().toString()))));
        o = opp();
        assertThat(followUpEngine.scheduled(o.id)).isEmpty();
        assertThat(followUps.findByOpportunityIdOrderByNumberAsc(o.id)).extracting(f -> f.status)
                .containsExactly(FollowUpStatus.DONE, FollowUpStatus.CANCELLED);
        assertThat(o.status).isEqualTo(OpportunityStatus.CONTRACT_TO_SIGN);
        Task sign = open(o).stream().filter(t -> t.type == TaskType.SIGN_CONTRACT).findFirst().orElseThrow();
        assertThat(sign.dueDate).isEqualTo(base.plusDays(9).toLocalDate());

        // Product arrives, content due in 4 weeks -> content task with that deadline
        LocalDate due = base.plusDays(40).toLocalDate();
        email(Direction.INBOUND, 10, "Your product arrived! Content due in 4 weeks.", analysis(Intent.PRODUCT_DELIVERED, brand, false,
                List.of(deadline(DeadlineType.CONTENT_DUE, due.toString()))));
        o = opp();
        assertThat(o.status).isEqualTo(OpportunityStatus.CONTENT_TO_CREATE);
        assertThat(open(o)).anyMatch(t -> t.type == TaskType.CREATE_CONTENT && due.equals(t.dueDate));

        // Digest shows the overdue contract signing as urgent
        assertThat(digest.morning().urgent()).anyMatch(i -> i.opportunityId().equals(opp().id) && i.overdueDays() > 0);

        // Brand pulls out -> closed, open work dismissed
        email(Direction.INBOUND, 12, "Unfortunately we're pausing the campaign.", analysis(Intent.DECLINE, brand, false, List.of()));
        o = opp();
        assertThat(o.status).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(open(o)).isEmpty();
    }

    @Test
    void pitchIsFollowedUpFiveTimesThenGoesCold() {
        String brand = "Luma " + UUID.randomUUID().toString().substring(0, 6);
        LocalDate pitched = LocalDate.now().minusDays(80);
        Opportunity o = outreach.logPitch(new OutreachService.PitchRequest(brand, "Sam", "sam@luma.test", null,
                "EMAIL", "UGC", pitched, null, false));
        assertThat(o.status).isEqualTo(OpportunityStatus.PITCHED);

        int[] cadence = {4, 5, 7, 7, 7};
        LocalDate expected = pitched;
        for (int n = 1; n <= 5; n++) {
            expected = expected.plusDays(cadence[n - 1]);
            FollowUp fu = followUpEngine.scheduled(o.id).orElseThrow();
            assertThat(fu.number).isEqualTo(n);
            assertThat(fu.scheduledDate).isEqualTo(expected);
            workflow.onCreatorMessage(o, Intent.CREATOR_FOLLOW_UP, fu.scheduledDate);
        }
        assertThat(followUpEngine.scheduled(o.id)).as("no #6").isEmpty();

        assertThat(followUpEngine.markColdDeals(LocalDate.now())).isGreaterThanOrEqualTo(1);
        assertThat(opportunities.findById(o.id).orElseThrow().status).isEqualTo(OpportunityStatus.COLD);
    }

    @Test
    void duplicatePitchIsRejectedUnlessForced() {
        String brand = "Bloom " + UUID.randomUUID().toString().substring(0, 6);
        outreach.logPitch(new OutreachService.PitchRequest(brand, null, null, null, "EMAIL", "", null, null, false));
        assertThatThrownBy(() -> outreach.logPitch(new OutreachService.PitchRequest(brand.toUpperCase() + " Inc", null, null, null,
                "EMAIL", "", null, null, false))).isInstanceOf(OutreachService.DuplicatePitchException.class);
        assertThat(outreach.logPitch(new OutreachService.PitchRequest(brand, null, null, null, "EMAIL", "", null, null, true)).id).isNotNull();
    }

    @Test
    void newslettersAreSkippedBeforeAiAndNonBrandMailCreatesNothing() {
        thread = "news" + UUID.randomUUID().toString().substring(0, 6);
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, Direction.INBOUND,
                "newsletter@shop.test", "Shop", "me@creator.test", "newsletter@shop.test", "Weekly deals",
                "50% off everything", "", "", base, true)));
        assertThat(messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc())
                .noneMatch(m -> "50% off everything".equals(m.content));

        thread = "mom" + UUID.randomUUID().toString().substring(0, 6);
        email(Direction.INBOUND, 0, "Dinner on Sunday?", analysis(Intent.NOT_BRAND_RELATED, "", false, List.of()));
        Long convId = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        assertThat(opportunities.findFirstByConversationIdOrderByIdDesc(convId)).isEmpty();
        assertThat(llmClient).isSameAs(llm);
    }
}
