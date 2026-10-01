package com.creatorcrm.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private HttpServer api;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private volatile String replyText;

    @BeforeEach
    void startStub() throws Exception {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/v1/messages", ex -> {
            lastRequest.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = JsonMapper.shared().writeValueAsString(Map.of(
                    "id", "msg_test", "type", "message", "role", "assistant", "model", "claude-opus-5-5",
                    "content", List.of(Map.of("type", "text", "text", replyText)),
                    "stop_reason", "end_turn",
                    "usage", Map.of("input_tokens", 10, "output_tokens", 10)));
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
                "", "", List.of(), ""));
        assertThat(d.body()).contains("$500");
    }

    @Test
    void malformedReplyIsAReadableError() {
        replyText = "not json";
        assertThatThrownBy(() -> claude.classify(sample()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("expected format");
    }
}
