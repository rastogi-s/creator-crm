package com.creatorcrm.scoring;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.ChannelConnector.SentMessage;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.digest.DigestService;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.web.CrmController;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Lead scores: the rules, where they show up, and declining several leads at once. */
@SpringBootTest
@ActiveProfiles("test")
class LeadScoringIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @MockitoSpyBean GmailConnector gmail;
    @Autowired FakeLlm llm;
    @Autowired LeadScoring scoring;
    @Autowired BatchDecline batch;
    @Autowired IngestionService ingestion;
    @Autowired ConversationRepo conversations;
    @Autowired OpportunityRepo opportunities;
    @Autowired MessageRepo messages;
    @Autowired DraftRepo drafts;
    @Autowired DigestService digest;
    @Autowired CrmController crm;

    private static final LeadScoring.Context USUAL_800 = new LeadScoring.Context(new BigDecimal("800"), Set.of());

    @BeforeEach
    void reset() throws Exception {
        llm.next.clear();
        llm.failDraftsWith = null;
        doReturn(true).when(gmail).isConnected();
        doReturn(Optional.empty()).when(gmail).pushDraft(any());
        doAnswer(inv -> new SentMessage(UUID.randomUUID().toString(), UUID.randomUUID().toString())).when(gmail).send(any());
        // The database is shared with other test classes: set aside their unanalyzed messages and pending declines.
        for (Message m : messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc()) {
            m.filteredReason = "left over from another test";
            messages.save(m);
        }
        for (Draft d : drafts.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING)) {
            if (d.type == DraftType.DECLINE) {
                d.status = DraftStatus.DISCARDED;
                drafts.save(d);
            }
        }
    }

    private static Opportunity lead(Compensation comp, OpportunityType type, String budget) {
        Opportunity o = new Opportunity();
        o.id = 1L;
        o.brandId = 7L;
        o.origin = Origin.INBOUND;
        o.status = OpportunityStatus.NEW_LEAD;
        o.compensation = comp;
        o.type = type;
        o.budgetAmount = budget == null ? null : new BigDecimal(budget);
        return o;
    }

    @Test
    void scoresComeFromWhatTheBrandOffered() {
        LeadScoring.Score usual = scoring.score(lead(Compensation.PAID, OpportunityType.PAID, "800"), USUAL_800);
        assertThat(usual.level()).isEqualTo(LeadScoring.Level.HIGH);
        assertThat(usual.reasons()).containsExactly("Paid", "At or above your usual $800");

        assertThat(scoring.score(lead(Compensation.PAID, OpportunityType.PAID, "600"), USUAL_800).level())
                .isEqualTo(LeadScoring.Level.HIGH); // a bit under: 50 + 20 + 5
        LeadScoring.Score cheap = scoring.score(lead(Compensation.PAID, OpportunityType.PAID, "100"), USUAL_800);
        assertThat(cheap.level()).isEqualTo(LeadScoring.Level.LOW);
        assertThat(cheap.summary()).contains("Well under your usual $800");

        assertThat(scoring.score(lead(Compensation.GIFTED, OpportunityType.GIFTED, null), USUAL_800).level()).isEqualTo(LeadScoring.Level.LOW);
        LeadScoring.Score affiliate = scoring.score(lead(Compensation.AFFILIATE, OpportunityType.AFFILIATE, null), USUAL_800);
        assertThat(affiliate.level()).isEqualTo(LeadScoring.Level.LOW);
        assertThat(affiliate.reasons()).contains("Commission only", "No budget mentioned");

        LeadScoring.Score noBudget = scoring.score(lead(Compensation.PAID, OpportunityType.UGC, null), USUAL_800);
        assertThat(noBudget.level()).isEqualTo(LeadScoring.Level.MEDIUM);
        // Asking for rates is a good sign, and not having a budget yet is expected.
        assertThat(scoring.score(lead(Compensation.PAID, OpportunityType.RATES_REQUEST, null), USUAL_800).reasons())
                .contains("Asked for your rates").doesNotContain("No budget mentioned");
        assertThat(scoring.score(lead(Compensation.UNKNOWN, OpportunityType.CREATOR_APPLICATION, null), USUAL_800).level())
                .isEqualTo(LeadScoring.Level.LOW);

        // A brand that paid before gets a bump; without enough booked deals, budgets aren't compared.
        LeadScoring.Score repeat = scoring.score(lead(Compensation.PAID, OpportunityType.UGC, null),
                new LeadScoring.Context(new BigDecimal("800"), Set.of(7L)));
        assertThat(repeat.reasons()).contains("Paid you before");
        assertThat(repeat.points()).isEqualTo(noBudget.points() + 10);
        assertThat(scoring.score(lead(Compensation.PAID, OpportunityType.PAID, "100"), new LeadScoring.Context(null, Set.of())).reasons())
                .containsExactly("Paid");
    }

    @Test
    void medianAndWhichDealsAreLeads() {
        assertThat(LeadScoring.median(List.of(new BigDecimal("500"), new BigDecimal("700"), new BigDecimal("900")))).isEqualByComparingTo("700");
        assertThat(LeadScoring.median(List.of(new BigDecimal("500"), new BigDecimal("900")))).isEqualByComparingTo("700");
        Opportunity o = lead(Compensation.PAID, OpportunityType.PAID, "500");
        assertThat(LeadScoring.isLead(o)).isTrue();
        o.status = OpportunityStatus.CONTENT_TO_CREATE;
        assertThat(LeadScoring.isLead(o)).isFalse(); // already booked
        o.status = OpportunityStatus.NEW_LEAD;
        o.origin = Origin.PITCH;
        assertThat(LeadScoring.isLead(o)).isFalse(); // her own pitch
    }

    private Opportunity inboundLead() {
        String thread = "lead" + UUID.randomUUID().toString().substring(0, 8);
        llm.next.add(analysis(Intent.NEW_OPPORTUNITY, "Brand " + thread, true, List.of()));
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, Direction.INBOUND,
                "maya@" + thread + ".test", "Maya", "me@creator.test", "maya@" + thread + ".test", "Collab?",
                "Would you make a UGC video for us?", "<" + UUID.randomUUID() + "@mail>", "", OffsetDateTime.now().minusHours(1), false)));
        ingestion.processPending();
        Long conv = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        return opportunities.findFirstByConversationIdOrderByIdDesc(conv).orElseThrow();
    }

    @Test
    void scoresShowOnPipelineAndToday() {
        Opportunity o = inboundLead();
        CrmController.OpportunityView row = crm.pipeline(false).stream().filter(r -> r.id().equals(o.id)).findFirst().orElseThrow();
        assertThat(row.lead()).isNotNull();
        assertThat(row.leadWhy()).contains("Paid");
        assertThat(digest.morning().urgent()).filteredOn(i -> o.id.equals(i.opportunityId()) && "TASK".equals(i.kind()))
                .isNotEmpty().allSatisfy(i -> assertThat(i.lead()).isEqualTo(row.lead()));
    }

    @Test
    void declinesAreDraftedForThePickedLeadsAndSentTogether() {
        Opportunity a = inboundLead();
        Opportunity b = inboundLead();

        BatchDecline.Result drafted = batch.draftDeclines(List.of(a.id, b.id));
        assertThat(drafted.done()).isEqualTo(2);
        assertThat(llm.lastDraftInput.draftType()).isEqualTo("DECLINE");
        assertThat(llm.lastDraftInput.extraInstructions()).contains("Politely decline");
        // Asking again doesn't write a second one.
        BatchDecline.Result again = batch.draftDeclines(List.of(a.id));
        assertThat(again.done()).isZero();
        assertThat(again.skipped()).singleElement().asString().contains("already in Drafts");

        BatchDecline.Result sent = batch.sendDeclines();
        assertThat(sent.done()).isEqualTo(2);
        for (Opportunity o : List.of(a, b)) {
            Opportunity now = opportunities.findById(o.id).orElseThrow();
            assertThat(now.status).isEqualTo(OpportunityStatus.CLOSED);
            assertThat(now.closedReason).isEqualTo("Declined by creator");
        }
        assertThat(batch.draftDeclines(List.of(a.id)).skipped()).singleElement().asString().contains("already closed");
        assertThatThrownBy(() -> batch.draftDeclines(List.of())).hasMessageContaining("at least one");
    }
}
