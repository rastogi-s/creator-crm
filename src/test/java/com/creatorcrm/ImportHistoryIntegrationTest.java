package com.creatorcrm;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.channels.PartialFetchException;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.LlmException;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** "Import older email": reach back, fetch in rounds, and never move a deal backwards with old mail. */
@SpringBootTest
@ActiveProfiles("test")
class ImportHistoryIntegrationTest {

    /** A mailbox that hands out at most 2 new messages per call, like Gmail's per-sync cap. */
    static class Mailbox implements ChannelConnector {
        final List<NormalizedMessage> mail = new ArrayList<>();
        final List<OffsetDateTime> sinces = new ArrayList<>();

        public Platform platform() { return Platform.OTHER; }
        public boolean isConnected() { return true; }
        public String accountLabel() { return "mailbox"; }
        public SentMessage send(Draft draft) { throw new UnsupportedOperationException(); }

        public List<NormalizedMessage> fetchSince(OffsetDateTime since) throws Exception {
            return fetchSince(since, id -> false);
        }

        public List<NormalizedMessage> fetchSince(OffsetDateTime since, Predicate<String> known) throws Exception {
            sinces.add(since);
            List<NormalizedMessage> fresh = mail.stream()
                    .filter(m -> m.sentAt().isAfter(since) && !known.test(m.externalId())).toList();
            if (fresh.size() > 2) throw new PartialFetchException("cap", fresh.subList(0, 2), null);
            return fresh;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        Mailbox mailbox() {
            return new Mailbox();
        }

        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired Mailbox mailbox;
    @Autowired FakeLlm llm;
    @Autowired IngestionService ingestion;
    @Autowired MessageRepo messages;
    @Autowired ConversationRepo conversations;
    @Autowired OpportunityRepo opportunities;

    @Autowired AppStateRepo appState;

    @BeforeEach
    void reset() {
        appState.findAll().stream().filter(s -> s.stateKey.startsWith("sync.OTHER")).forEach(appState::delete);
        mailbox.mail.clear();
        mailbox.sinces.clear();
        llm.next.clear();
        llm.draftCalls = 0;
        llm.classifyCalls = 0;
        llm.failWith = null;
        llm.batch = false;
        llm.batches.clear();
        appState.findAll().stream().filter(s -> s.stateKey.startsWith("ai.")).forEach(appState::delete);
        // Earlier tests may leave unanalyzed mail behind; mark it handled so each test sees only its own.
        messages.findAll().stream().filter(m -> !m.aiProcessed && m.filteredReason == null)
                .forEach(m -> { m.filteredReason = "test reset"; messages.save(m); });
    }

    private static NormalizedMessage mail(String thread, int daysAgo, String text) {
        return new NormalizedMessage(Platform.OTHER, UUID.randomUUID().toString(), thread, Direction.INBOUND,
                "maya@" + thread + ".test", "Maya", "me@creator.test", "maya@" + thread + ".test", "Collab", text,
                "", "", OffsetDateTime.now().minusDays(daysAgo), false);
    }

    private Message stored(NormalizedMessage nm) {
        return messages.findAll().stream().filter(m -> m.externalId.equals("other:" + nm.externalId())).findFirst().orElseThrow();
    }

    @Test
    void importReachesBackFetchesInRoundsAndFinishes() {
        String t = "imp" + UUID.randomUUID().toString().substring(0, 6);
        for (int d = 1; d <= 5; d++) mailbox.mail.add(mail(t, 60 + d, "old note " + d)); // 61-65 days ago
        llm.next.add(analysis(Intent.NOT_BRAND_RELATED, "", false, List.of()));
        ingestion.syncAll(); // normal sync: default lookback doesn't reach them
        assertThat(mailbox.mail).noneMatch(m -> messages.existsByExternalId("other:" + m.externalId()));

        ingestion.startImport(Platform.OTHER, 90);
        assertThat(ingestion.status().importingSince()).isNotNull();
        for (int i = 0; i < 5; i++) llm.next.add(analysis(Intent.NOT_BRAND_RELATED, "", false, List.of()));

        var r = ingestion.syncAll();
        assertThat(r.channels().get("OTHER")).isEqualTo("5 new"); // 3 capped rounds in one sync
        assertThat(mailbox.sinces.get(mailbox.sinces.size() - 1))
                .isCloseTo(OffsetDateTime.now().minusDays(90), within(1, ChronoUnit.MINUTES));
        assertThat(ingestion.status().importingSince()).isNull(); // done
        assertThat(mailbox.mail).allMatch(m -> messages.existsByExternalId("other:" + m.externalId()));
    }

    @Test
    void analysisFailuresAreShownNotSilent() {
        String t = "err" + UUID.randomUUID().toString().substring(0, 6);
        ingestion.store(List.of(mail(t, 3, "one"), mail(t, 2, "two")));

        // Something specific to a message failing (here: no scripted analysis): shown, and the run keeps going.
        ingestion.processPending();
        assertThat(llm.classifyCalls).isEqualTo(2);
        assertThat(ingestion.status().aiError()).contains("Analysis failed for one message");

        // Claude itself failing: shown, and the run stops after 3 in a row instead of trying everything.
        for (int i = 0; i < 5; i++) ingestion.store(List.of(mail(t + i, 1, "more " + i)));
        llm.classifyCalls = 0;
        llm.failWith = new LlmException("Claude request failed: AnthropicIoException: timeout");
        ingestion.processPending();
        assertThat(llm.classifyCalls).isEqualTo(3);
        assertThat(ingestion.status().aiError()).contains("AnthropicIoException: timeout");
        assertThat(ingestion.status().waitingForAi()).isEqualTo(7);
    }

    @Test
    void oldMailNeverMovesADealBackwardsAndGetsNoDrafts() {
        String t = "deal" + UUID.randomUUID().toString().substring(0, 6);
        String brand = "Glow " + t;

        // This week: the brand sent the contract.
        NormalizedMessage contract = mail(t, 1, "Contract attached");
        ingestion.store(List.of(contract));
        llm.next.add(analysis(Intent.CONTRACT_SENT, brand, true, List.of()));
        ingestion.processPending();
        Long convId = conversations.findByPlatformAndExternalId(Platform.OTHER, t).orElseThrow().id;
        assertThat(opportunities.findFirstByConversationIdOrderByIdDesc(convId).orElseThrow().status)
                .isEqualTo(OpportunityStatus.CONTRACT_TO_SIGN);

        // Import brings in their first email from 2 months ago: context only, no AI call, status unchanged.
        NormalizedMessage ratesAsk = mail(t, 60, "Can you send your rates?");
        ingestion.store(List.of(ratesAsk));
        ingestion.processPending();
        assertThat(stored(ratesAsk).aiProcessed).isFalse();
        assertThat(stored(ratesAsk).filteredReason).contains("kept as context");
        assertThat(opportunities.findFirstByConversationIdOrderByIdDesc(convId).orElseThrow().status)
                .isEqualTo(OpportunityStatus.CONTRACT_TO_SIGN);

        // A brand-new thread from 3 months ago is analyzed (the deal appears) but gets no reply draft.
        String old = "old" + UUID.randomUUID().toString().substring(0, 6);
        ingestion.store(List.of(mail(old, 90, "Can you send your rates?")));
        llm.next.add(analysis(Intent.RATES_REQUEST, "Old " + old, true, List.of()));
        ingestion.processPending();
        Long oldConv = conversations.findByPlatformAndExternalId(Platform.OTHER, old).orElseThrow().id;
        assertThat(opportunities.findFirstByConversationIdOrderByIdDesc(oldConv)).isPresent();
        assertThat(llm.draftCalls).isZero();

        // Within a thread, only the latest unanswered email gets a draft; earlier ones would just be superseded.
        String recent = "rec" + UUID.randomUUID().toString().substring(0, 6);
        ingestion.store(List.of(mail(recent, 20, "Can you send your rates?"), mail(recent, 19, "Also your media kit?")));
        llm.next.add(analysis(Intent.RATES_REQUEST, "Rec " + recent, true, List.of()));
        llm.next.add(analysis(Intent.MEDIA_KIT_REQUEST, "Rec " + recent, true, List.of()));
        ingestion.processPending();
        assertThat(llm.draftCalls).isEqualTo(1);
    }

    @Test
    void bigImportsAreAnalyzedInHalfPriceBatchesOneMessagePerThreadAtATime() {
        llm.batch = true;
        String id = UUID.randomUUID().toString().substring(0, 6);
        NormalizedMessage first = mail("a" + id, 40, "Can you send your rates?");
        NormalizedMessage second = mail("a" + id, 39, "Following up on rates");
        List<NormalizedMessage> old = new ArrayList<>(List.of(first, second));
        for (int i = 0; i < 19; i++) old.add(mail("s" + i + id, 30, "Old note " + i));
        NormalizedMessage fresh = mail("new" + id, 1, "New collab?");
        ingestion.store(old);
        ingestion.store(List.of(fresh));
        llm.next.add(analysis(Intent.NOT_BRAND_RELATED, "", false, List.of()));

        // Round 1: the new email is analyzed now; the oldest waiting message of each old thread goes in one batch.
        ingestion.processPending();
        assertThat(llm.classifyCalls).isEqualTo(1);
        assertThat(stored(fresh).aiProcessed).isTrue();
        assertThat(llm.batches).hasSize(1);
        assertThat(llm.batches.getFirst()).hasSize(20)
                .containsKey(stored(first).id.toString()).doesNotContainKey(stored(second).id.toString());
        assertThat(ingestion.status().inBatch()).isEqualTo(20);
        assertThat(ingestion.batchInFlight()).isTrue();

        // Still running: nothing changes, nothing is sent twice.
        ingestion.processPending();
        assertThat(llm.batches).hasSize(1);
        assertThat(llm.classifyCalls).isEqualTo(1);
        assertThat(stored(first).aiProcessed).isFalse();

        // Finished: results are applied, then the thread's next message goes (only one left, so right away).
        llm.batchDone = true;
        llm.next.add(analysis(Intent.NOT_BRAND_RELATED, "", false, List.of()));
        ingestion.processPending();
        assertThat(old).allMatch(m -> stored(m).aiProcessed);
        assertThat(llm.classifyCalls).isEqualTo(2);
        assertThat(ingestion.batchInFlight()).isFalse();
        assertThat(ingestion.status().inBatch()).isZero();
    }

    @Test
    void aFewOldMessagesAreNotWorthABatch() {
        llm.batch = true;
        String t = "few" + UUID.randomUUID().toString().substring(0, 6);
        ingestion.store(List.of(mail(t, 30, "Old one")));
        llm.next.add(analysis(Intent.NOT_BRAND_RELATED, "", false, List.of()));
        ingestion.processPending();
        assertThat(llm.batches).isEmpty();
        assertThat(llm.classifyCalls).isEqualTo(1);
    }
}
