package com.creatorcrm.practice;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** In practice mode Send moves the deal along like a real send, with Gmail not connected and nothing sent. */
@SpringBootTest(properties = {"crm.practice=true",
        "spring.datasource.url=jdbc:h2:mem:practicesend;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class PracticeSendIntegrationTest {

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

    @Test
    void sendPretendsAndStillRefusesBlanks() {
        String thread = "practice" + UUID.randomUUID().toString().substring(0, 8);
        llm.next.add(analysis(Intent.RATES_REQUEST, "Glow " + thread, true, List.of()));
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, Direction.INBOUND,
                "maya@" + thread + ".test", "Maya", "me@creator.test", "maya@" + thread + ".test",
                "Collab", "What are your rates?", "<" + UUID.randomUUID() + "@mail>", "", OffsetDateTime.now().minusHours(2), false)));
        ingestion.processPending();
        Long convId = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(convId).orElseThrow();
        Draft d = drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).getFirst();

        assertThat(draftService.sendBlockedReason(d)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> draftService.send(d.id, null, "My rate is [RATE FOR 1 REEL]."))
                .hasMessageContaining("[RATE FOR 1 REEL]");

        Draft sent = draftService.send(d.id, null, "Hi Maya, my rate for one Reel is $500.");
        assertThat(sent.status).isEqualTo(DraftStatus.SENT);
        assertThat(sent.sentAt).isNotNull();
        assertThat(sent.body).contains("$500");
    }
}
