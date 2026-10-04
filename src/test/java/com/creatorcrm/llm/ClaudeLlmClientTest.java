package com.creatorcrm.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real Claude client against a local stand-in for the API: schema generation, request and reply parsing all
 * run for real. (Other tests use a fake client, which is how a library clash once shipped unnoticed.)
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaudeLlmClientTest {

    @Autowired ClaudeLlmClient claude;
    @Autowired SecretStore secrets;
    @Autowired ClaudeSpend spend;

    private HttpServer api;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private volatile String replyText;
    private final java.util.Deque<String> stopReasons = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Map<String, Object> usage = Map.of("input_tokens", 10, "output_tokens", 10);
    private volatile int errorStatus;
    private volatile String errorBody;

    @BeforeEach
    void startStub() throws Exception {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/v1/messages", ex -> {
            lastRequest.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(lastRequest.get());
            if (errorStatus != 0) {
                byte[] err = errorBody.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(errorStatus, err.length);
                ex.getResponseBody().write(err);
                ex.close();
                return;
            }
            String stop = stopReasons.isEmpty() ? "end_turn" : stopReasons.poll();
            String body = JsonMapper.shared().writeValueAsString(Map.of(
                    "id", "msg_test", "type", "message", "role", "assistant", "model", "claude-opus-5-5",
                    "content", List.of(Map.of("type", "text", "text", replyText)),
                    "stop_reason", stop,
                    "usage", usage));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        api.start();
        secrets.put(SecretName.ANTHROPIC_API_KEY, "sk-ant-test-key");
        claude.useBaseUrl("http://127.0.0.1:" + api.getAddress().getPort());
    }

    @AfterEach
    void stopStub() {
        spend.setBalance(null);
        claude.useBaseUrl(null);
        api.stop(0);
    }

    private static List<String> strings(Object list) {
        return ((List<?>) list).stream().map(String::valueOf).toList();
    }

    private static ClassificationInput sample() {
        return new ClassificationInput(LocalDate.now(), "EMAIL", "INBOUND", "", "", List.of(),
                "Hi! Could you share your rates for one UGC video?");
    }

    @Test
    void classifiesWithAStrictSchemaAndParsesTheReply() throws Exception {
        replyText = """
                {"brandRelated": true, "brandName": "Glow Co", "contactName": "Maya", "intent": "RATES_REQUEST",
                 "opportunityType": "UGC", "compensation": "PAID", "budgetAmount": 0, "currency": "",
                 "budgetText": "", "deliverables": "1 UGC video", "usageRights": "", "campaign": "",
                 "deadlines": [], "missingInfo": ["budget"], "requiresReply": true, "urgency": "MEDIUM",
                 "suggestedAction": "Reply to Glow Co with UGC rates", "updatedSummary": "Glow Co asked for rates."}
                """;

        MessageAnalysis a = claude.classify(sample());
        assertThat(a.brandName()).isEqualTo("Glow Co");
        assertThat(a.intent()).isEqualTo(Intent.RATES_REQUEST);
        assertThat(a.requiresReply()).isTrue();

        // What we sent: a JSON schema the API accepts for structured outputs.
        Map<?, ?> req = JsonMapper.shared().readValue(lastRequest.get(), Map.class);
        Map<?, ?> schema = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) req.get("output_config")).get("format")).get("schema");
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat(strings(schema.get("required"))).contains("brandRelated", "intent", "updatedSummary", "deadlines");
        Map<?, ?> props = (Map<?, ?>) schema.get("properties");
        assertThat(((Map<?, ?>) props.get("brandName")).get("description")).asString().contains("brand's name");
        assertThat(strings(((Map<?, ?>) props.get("intent")).get("enum"))).contains("RATES_REQUEST", "CONTRACT_SENT");
    }

    @Test
    void writesDrafts() {
        replyText = "{\"subject\": \"Re: Collab\", \"body\": \"Hi Maya, my rate is $500.\"}";
        DraftText d = claude.writeDraft(new DraftInput(LocalDate.now(), "RATES", "EMAIL", "Glow Co", "Maya",
                "", "", List.of(), "", List.of()));
        assertThat(d.body()).contains("$500");
    }

    @Test
    void pastExamplesAreSentToTheWriter() {
        replyText = "{\"subject\": \"Re: Collab\", \"body\": \"Hi!\"}";
        claude.writeDraft(new DraftInput(LocalDate.now(), "RATES", "EMAIL", "Glow Co", "Maya", "", "", List.of(), "",
                List.of("<past_example kind=\"RATES\">My reel rate is in my media kit</past_example>")));
        assertThat(lastRequest.get()).contains("Messages the creator sent before").contains("My reel rate is in my media kit");
    }

    @Test
    void brandResearchUsesWebSearchAndResumesPausedTurns() {
        replyText = "{\"leads\": [{\"name\": \"Glow Co\", \"website\": \"https://glow.example\", \"instagram\": \"glowco\","
                + " \"contactEmail\": \"collabs@glow.example\", \"contactSourceUrl\": \"https://glow.example/contact\","
                + " \"fitReason\": \"Runs a UGC program\", \"pitchAngle\": \"Morning routine reel\"}]}";
        stopReasons.add("pause_turn"); // the server-side search loop pauses once

        BrandLeads leads = claude.findBrands(new BrandSearchInput("clean skincare", 3, List.of("Bloom")));

        assertThat(leads.leads()).extracting(BrandLeads.Lead::name).containsExactly("Glow Co");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).contains("web_search_20260209").contains("clean skincare").contains("Bloom");
        assertThat(requests.get(1)).contains("\"role\":\"assistant\""); // the paused turn is sent back
    }

    @Test
    void malformedReplyIsAReadableError() {
        replyText = "not json";
        assertThatThrownBy(() -> claude.classify(sample()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("expected format");
    }

    @Test
    void eachReplyAddsItsCostToTheSpendTotals() {
        replyText = "{\"subject\": \"Re: Collab\", \"body\": \"Hi!\"}";
        usage = Map.of("input_tokens", 1_000_000, "output_tokens", 100_000, "cache_read_input_tokens", 1_000_000,
                "cache_creation_input_tokens", 0, "server_tool_use", Map.of("web_search_requests", 2));
        ClaudeSpend.Summary before = spend.summary();

        claude.writeDraft(new DraftInput(LocalDate.now(), "RATES", "EMAIL", "Glow Co", "Maya", "", "", List.of(), "", List.of()));

        // Opus 5.5: $4 input + $2 output + $0.20 cache reads + 2 searches × $0.01
        ClaudeSpend.Summary after = spend.summary();
        assertThat(after.trackedUsd() - before.trackedUsd()).isCloseTo(6.22, offset(0.011));
        assertThat(after.byFeature().get("DRAFT") - before.byFeature().get("DRAFT")).isCloseTo(6.22, offset(0.011));
        assertThat(after.calls()).isEqualTo(before.calls() + 1);
        assertThat(after.trackedSince()).isNotNull();
        assertThat(after.totalUsd()).isEqualTo(after.trackedUsd() + after.beforeUsd(), offset(0.011));
    }

    @Test
    void lowBalanceWarnsAndRunningOutIsDetectedAndClearsOnTheNextReply() {
        replyText = "{\"subject\": \"Re: Collab\", \"body\": \"Hi!\"}";
        usage = Map.of("input_tokens", 1_000_000, "output_tokens", 0); // $4 per call
        spend.setBalance(10.0);
        assertThat(spend.summary().alert()).isNull();

        claude.writeDraft(new DraftInput(LocalDate.now(), "RATES", "EMAIL", "Glow Co", "", "", "", List.of(), "", List.of()));
        claude.writeDraft(new DraftInput(LocalDate.now(), "RATES", "EMAIL", "Glow Co", "", "", "", List.of(), "", List.of()));
        ClaudeSpend.Summary low = spend.summary();
        assertThat(low.remainingUsd()).isCloseTo(2.0, offset(0.011));
        assertThat(low.alert().level()).isEqualTo("LOW");

        // The prepaid balance is used up: the API answers 400 with this message.
        errorStatus = 400;
        errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":"
                + "\"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.\"}}";
        assertThatThrownBy(() -> claude.classify(sample())).isInstanceOf(OutOfCreditsException.class);
        assertThat(spend.summary().alert().level()).isEqualTo("OUT");
        assertThat(spend.outOfCredits()).isTrue();

        errorStatus = 0;
        claude.writeDraft(new DraftInput(LocalDate.now(), "RATES", "EMAIL", "Glow Co", "", "", "", List.of(), "", List.of()));
        assertThat(spend.outOfCredits()).isFalse();
    }

    @Test
    void billingErrorCountsAsOutOfCredits() {
        errorStatus = 402;
        errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"billing_error\",\"message\":\"Billing problem\"}}";
        assertThatThrownBy(() -> claude.classify(sample())).isInstanceOf(OutOfCreditsException.class);
        assertThat(spend.outOfCredits()).isTrue();
        spend.setBalance(20.0); // entering a new balance after topping up clears it
        assertThat(spend.outOfCredits()).isFalse();
        errorStatus = 0;
    }

    @Test
    void otherBadRequestsAreNotCreditProblems() {
        errorStatus = 400;
        errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: too large\"}}";
        assertThatThrownBy(() -> claude.classify(sample())).isInstanceOf(LlmException.class)
                .isNotInstanceOf(OutOfCreditsException.class);
        assertThat(spend.outOfCredits()).isFalse();
        errorStatus = 0;
    }
}
