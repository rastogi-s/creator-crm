package com.creatorcrm.drafts;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
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

/** "Ask Claude to change this": a pending draft is rewritten in place and still waits for Send. */
@SpringBootTest
@ActiveProfiles("test")
class DraftRevisionIntegrationTest {

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
    @Autowired ConversationRepo conversations;
    @Autowired OpportunityRepo opportunities;
    @Autowired DraftRepo drafts;
    @Autowired MessageRepo messages;
    @Autowired DraftService draftService;

    @BeforeEach
    void reset() {
        llm.next.clear();
        llm.failDraftsWith = null;
        messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc().forEach(m -> {
            m.aiProcessed = true;
            messages.save(m);
        });
    }

    private Draft pendingDraft() {
        String thread = "revise" + UUID.randomUUID().toString().substring(0, 8);
        llm.next.add(analysis(Intent.RATES_REQUEST, "Glow " + thread, true, List.of()));
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, Direction.INBOUND,
                "maya@" + thread + ".test", "Maya", "me@creator.test", "maya@" + thread + ".test",
                "Collab", "What are your rates?", "<" + UUID.randomUUID() + "@mail>", "", OffsetDateTime.now().minusHours(2), false)));
        ingestion.processPending();
        Long convId = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(convId).orElseThrow();
        return drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).getFirst();
    }

    @Test
    void rewritesTheTextShownAndKeepsItPending() {
        Draft d = pendingDraft();
        String original = d.originalBody;

        // Starts from what's on screen, unsaved edits included, with the deal's context.
        Draft r = draftService.revise(d.id, "Re: Collab", "Hi Maya, my rate is $800.", "  Make it warmer ");
        assertThat(llm.lastRevisionRequest).isEqualTo("Make it warmer");
        assertThat(llm.lastRevisionCurrent.body()).isEqualTo("Hi Maya, my rate is $800.");
        assertThat(llm.lastRevisionInput.draftType()).isEqualTo("RATES");
        assertThat(llm.lastRevisionInput.recentMessages()).anyMatch(m -> m.contains("What are your rates?"));

        assertThat(r.status).isEqualTo(DraftStatus.PENDING);
        assertThat(r.body).isEqualTo("Hi Maya, my rate is $800. [Make it warmer]");
        assertThat(r.subject).isEqualTo("Re: Collab");
        // Claude's first draft is kept, so learning counts the change as the creator's.
        assertThat(drafts.findById(d.id).orElseThrow().originalBody).isEqualTo(original);
    }

    @Test
    void emptyTextFallsBackToTheSavedDraft() {
        Draft d = pendingDraft();
        draftService.revise(d.id, null, "", "Shorter");
        assertThat(llm.lastRevisionCurrent.body()).isEqualTo(d.body);
    }

    @Test
    void needsARequestAndAPendingDraft() {
        Draft d = pendingDraft();
        assertThatThrownBy(() -> draftService.revise(d.id, null, null, " ")).isInstanceOf(IllegalArgumentException.class);
        draftService.discard(d.id);
        assertThatThrownBy(() -> draftService.revise(d.id, null, null, "Shorter")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedRewriteLeavesTheDraftAlone() {
        Draft d = pendingDraft();
        llm.failDraftsWith = new IllegalStateException("Claude is down");
        assertThatThrownBy(() -> draftService.revise(d.id, null, "Changed on screen", "Shorter")).hasMessageContaining("down");
        assertThat(drafts.findById(d.id).orElseThrow().body).isEqualTo(d.body);
    }
}
