package com.creatorcrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.channels.PartialFetchException;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.MessageRepo;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/** A rate limit mid-sync must keep what was fetched and resume (not restart) on the next sync. */
@SpringBootTest
@ActiveProfiles("test")
class PartialSyncIntegrationTest {

    /** Serves {@code total} messages but "rate limits" after 3 downloads per sync. */
    static class FlakyConnector implements ChannelConnector {
        final List<String> downloaded = new ArrayList<>();
        int total = 5;
        Instant retryAfter;
        RuntimeException failWith;

        public Platform platform() { return Platform.OTHER; }
        public boolean isConnected() { return true; }
        public String accountLabel() { return "flaky"; }
        public SentMessage send(Draft draft) { throw new UnsupportedOperationException(); }

        public List<NormalizedMessage> fetchSince(OffsetDateTime since) throws Exception {
            return fetchSince(since, id -> false);
        }

        public List<NormalizedMessage> fetchSince(OffsetDateTime since, Predicate<String> known) throws Exception {
            if (failWith != null) throw failWith;
            List<NormalizedMessage> out = new ArrayList<>();
            for (int i = 1; i <= total; i++) {
                String id = "m" + i;
                if (known.test(id)) continue;
                if (out.size() == 3) throw new PartialFetchException("429 rate limited", out, new RuntimeException("429"), retryAfter);
                downloaded.add(id);
                out.add(new NormalizedMessage(Platform.OTHER, id, "t-" + id, Direction.INBOUND, "a@b.test", "A",
                        "me@test", "a@b.test", "Hi", "hello", "", "", OffsetDateTime.now().minusMinutes(10 - i), true));
            }
            return out;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        FlakyConnector flakyConnector() {
            return new FlakyConnector();
        }
    }

    @Autowired FlakyConnector flaky;
    @Autowired IngestionService ingestion;
    @Autowired MessageRepo messages;
    @Autowired AppStateRepo appState;

    /** Test classes share one in-memory database; start this channel from scratch. */
    @BeforeEach
    void resetChannelState() {
        appState.findAll().stream().filter(s -> s.stateKey.startsWith("sync.OTHER")).forEach(appState::delete);
        flaky.failWith = null;
        flaky.total = 5;
        flaky.retryAfter = null;
        flaky.downloaded.clear();
    }

    @Test
    void anAccountThatNeedsSigningInAgainIsFlaggedUntilASyncWorks() {
        flaky.failWith = new RuntimeException("Connection reset");
        ingestion.syncAll();
        assertThat(problems()).as("one offline blip is not worth a banner").isEmpty();

        flaky.failWith = new RuntimeException("400 Bad Request: invalid_grant (Token has been expired or revoked.)");
        ingestion.syncAll();
        assertThat(problems()).singleElement().satisfies(p -> {
            assertThat(p.signIn()).isTrue();
            assertThat(p.detail()).contains("invalid_grant");
        });

        flaky.failWith = null;
        flaky.total = 0;
        ingestion.syncAll();
        assertThat(problems()).isEmpty();
    }

    private List<IngestionService.ChannelProblem> problems() {
        return ingestion.status().channelProblems().stream().filter(p -> p.channel().equals("OTHER")).toList();
    }

    @Test
    void rateLimitedSyncKeepsProgressAndResumes() {
        long before = ingestion.status().version();
        var first = ingestion.syncAll();
        // Pages poll this to know when to refresh. No Claude key in tests: the UI says so instead of staying blank.
        assertThat(ingestion.status().version()).isGreaterThan(before);
        assertThat(ingestion.status().syncing()).isFalse();
        assertThat(first.channels().get("OTHER")).isEqualTo("3 new, more pending");
        assertThat(ingestion.read("sync.OTHER")).isEmpty(); // cursor not advanced
        assertThat(ingestion.read("sync.OTHER.error")).hasValueSatisfying(e -> assertThat(e).contains("429"));

        var second = ingestion.syncAll();
        assertThat(second.channels().get("OTHER")).isEqualTo("2 new");
        assertThat(flaky.downloaded).containsExactly("m1", "m2", "m3", "m4", "m5"); // nothing downloaded twice
        assertThat(ingestion.read("sync.OTHER")).isPresent();
        assertThat(ingestion.read("sync.OTHER.error")).isEmpty();
        for (int i = 1; i <= 5; i++) assertThat(messages.existsByExternalId("other:m" + i)).isTrue();

        // Channel says "retry after": keep what we got, then don't call it again until then.
        flaky.total = 10;
        flaky.retryAfter = Instant.now().plus(Duration.ofMinutes(10));
        var third = ingestion.syncAll();
        assertThat(third.channels().get("OTHER")).isEqualTo("3 new, more pending");
        assertThat(ingestion.read("sync.OTHER.error")).hasValueSatisfying(e -> assertThat(e).contains("paused until"));

        var fourth = ingestion.syncAll();
        assertThat(fourth.channels().get("OTHER")).startsWith("rate limited, resuming after");
        assertThat(flaky.downloaded).hasSize(8); // not called while paused
    }
}
