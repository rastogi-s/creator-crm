package com.creatorcrm.rates;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import java.math.BigDecimal;
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

/** The rate advisor on real deals: when it has a suggestion, and the counter-offer draft it writes. */
@SpringBootTest
@ActiveProfiles("test")
class RateAdvisorIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired FakeLlm llm;
    @Autowired RateAdvisor advisor;
    @Autowired IngestionService ingestion;
    @Autowired ConversationRepo conversations;
    @Autowired OpportunityRepo opportunities;
    @Autowired MessageRepo messages;
    @Autowired DraftRepo drafts;
    @Autowired DraftService draftService;
    @Autowired SettingsService settings;

    private String savedProfile;

    @BeforeEach
    void reset() {
        llm.next.clear();
        llm.failDraftsWith = null;
        // The database is shared with other test classes: set aside their unanalyzed messages.
        for (Message m : messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc()) {
            m.filteredReason = "left over from another test";
            messages.save(m);
        }
        savedProfile = settings.creatorProfile();
        // Rates in About you, so there is always something to go on even before five paid deals exist.
        settings.update(Map.of(SettingsService.CREATOR_PROFILE, "# Rates\n- 1 Instagram Reel: $800\n- UGC video: $500\n"));
    }

    @AfterEach
    void restore() {
        settings.update(Map.of(SettingsService.CREATOR_PROFILE, savedProfile));
    }

    private Opportunity lead() {
        String thread = "rate" + UUID.randomUUID().toString().substring(0, 8);
        llm.next.add(analysis(Intent.NEGOTIATION, "Brand " + thread, true, List.of())); // $500 for 1 UGC video
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, Direction.INBOUND,
                "maya@" + thread + ".test", "Maya", "me@creator.test", "maya@" + thread + ".test", "Our offer",
                "We can do $500 for one UGC video.", "<" + UUID.randomUUID() + "@mail>", "", OffsetDateTime.now().minusHours(1), false)));
        ingestion.processPending();
        Long conv = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        return opportunities.findFirstByConversationIdOrderByIdDesc(conv).orElseThrow();
    }

    @Test
    void suggestsAPriceForWhatTheBrandAsked() {
        Opportunity o = lead();
        RateAdvisor.Advice a = advisor.advise(o.id);
        assertThat(a.available()).isTrue();
        assertThat(a.asks()).isEqualTo("1 UGC video");
        assertThat(a.suggested()).isPositive();
        assertThat(a.lines()).singleElement().extracting(RateAdvisor.Line::text).asString().startsWith("1 UGC video × $");
        assertThat(a.offer()).isEqualByComparingTo("500");
        assertThat(a.offerGapPercent()).isNotNull();

        // Nothing to suggest without deliverables, or for a gifted offer.
        o.deliverables = "";
        opportunities.save(o);
        assertThat(advisor.advise(o.id).available()).isFalse();
        assertThat(advisor.advise(o.id).why()).contains("Deliverables");
        o.deliverables = "1 Reel";
        o.compensation = Compensation.GIFTED;
        opportunities.save(o);
        assertThat(advisor.advise(o.id).why()).contains("Gifted");
    }

    @Test
    void counterOfferQuotesHerAmountAndReplacesTheUntouchedReply() {
        Opportunity o = lead();
        Draft earlier = draftService.generate(o.id, DraftType.NEGOTIATION, null, null, null);
        Draft edited = draftService.generate(o.id, DraftType.NEGOTIATION, null, null, null);
        edited.body = edited.body + "\nP.S. I edited this one.";
        drafts.save(edited);

        Draft counter = advisor.draftCounter(o.id, new BigDecimal("1200"));
        assertThat(counter.type).isEqualTo(DraftType.NEGOTIATION);
        assertThat(counter.status).isEqualTo(DraftStatus.PENDING);
        assertThat(llm.lastDraftInput.draftType()).isEqualTo("NEGOTIATION");
        assertThat(llm.lastDraftInput.extraInstructions()).contains("propose $1,200 for 1 UGC video").contains("quote it exactly");
        assertThat(drafts.findById(earlier.id).orElseThrow().status).isEqualTo(DraftStatus.SUPERSEDED);
        assertThat(drafts.findById(edited.id).orElseThrow().status).isEqualTo(DraftStatus.PENDING); // her edits are kept

        assertThatThrownBy(() -> advisor.draftCounter(o.id, BigDecimal.ZERO)).hasMessageContaining("amount");
        assertThatThrownBy(() -> advisor.draftCounter(o.id, null)).hasMessageContaining("amount");
    }
}
