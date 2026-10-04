package com.creatorcrm.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.creatorcrm.channels.instagram.InstagramStatsService;
import com.creatorcrm.links.LinkService;
import com.creatorcrm.security.CryptoService;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.creatorcrm.settings.SettingsService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** {@link LlmClient} backed by the Claude API. The API key comes from the encrypted credential store. */
@Component
public class ClaudeLlmClient implements LlmClient {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final SecretStore secrets;
    private final SettingsService settings;
    private final LinkService links;
    private final InstagramStatsService instagramStats;
    private final String classifierSystem;
    private final String writerSystem;
    private final String researchSystem;

    private AnthropicClient client;
    private String clientKeyHash;
    private String baseUrl; // null = the real API; tests point it at a local stub

    public ClaudeLlmClient(SecretStore secrets, SettingsService settings, LinkService links,
                           InstagramStatsService instagramStats) {
        this.secrets = secrets;
        this.settings = settings;
        this.links = links;
        this.instagramStats = instagramStats;
        this.classifierSystem = resource("prompts/classifier-system.md");
        this.writerSystem = resource("prompts/writer-system.md");
        this.researchSystem = resource("prompts/brand-research-system.md");
    }

    @Override
    public boolean isConfigured() {
        return secrets.has(SecretName.ANTHROPIC_API_KEY);
    }

    @Override
    public MessageAnalysis classify(ClassificationInput in) {
        StringBuilder user = new StringBuilder()
                .append("Today: ").append(in.today()).append('\n')
                .append("Channel: ").append(in.platform()).append('\n')
                .append("Direction of the new message: ").append(in.direction())
                .append(" (INBOUND = from the brand/other party, OUTBOUND = written by the creator)\n\n");
        if (!in.opportunityContext().isBlank()) {
            user.append("Known deal record:\n").append(in.opportunityContext()).append("\n\n");
        }
        if (!in.conversationSummary().isBlank()) {
            user.append("<conversation_summary>\n").append(Untrusted.escape(in.conversationSummary()))
                    .append("\n</conversation_summary>\n\n");
        }
        if (!in.recentMessages().isEmpty()) {
            user.append("Recent earlier messages (oldest first):\n");
            in.recentMessages().forEach(m -> user.append(m).append('\n'));
            user.append('\n');
        }
        user.append("NEW MESSAGE TO ANALYZE:\n").append(in.newMessage());

        MessageAnalysis a = call(settings.classifierModel(), settings.classifierEffort(), classifierSystem, null,
                user.toString(), MessageAnalysis.class, 4000, false);
        return AnalysisValidator.sanitize(a);
    }

    @Override
    public DraftText writeDraft(DraftInput in) {
        StringBuilder user = new StringBuilder()
                .append("Today: ").append(in.today()).append('\n')
                .append("Write a ").append(in.draftType()).append(" message via ").append(in.platform())
                .append(" to ").append(in.brandName())
                .append(in.contactName().isBlank() ? "" : " (contact: " + in.contactName() + ")").append(".\n\n");
        if (!in.opportunityContext().isBlank()) {
            user.append("Deal record:\n").append(in.opportunityContext()).append("\n\n");
        }
        if (!in.conversationSummary().isBlank()) {
            user.append("<conversation_summary>\n").append(Untrusted.escape(in.conversationSummary()))
                    .append("\n</conversation_summary>\n\n");
        }
        if (!in.recentMessages().isEmpty()) {
            user.append("Most recent messages (oldest first):\n");
            in.recentMessages().forEach(m -> user.append(m).append('\n'));
            user.append('\n');
        }
        if (!in.pastExamples().isEmpty()) {
            user.append("Messages the creator sent before. Match their voice, length, greeting and sign-off, and make the ")
                    .append("same kind of changes they made to your earlier drafts. Never copy names, numbers, dates, ")
                    .append("links or promises from them:\n");
            in.pastExamples().forEach(x -> user.append(x).append('\n'));
            user.append('\n');
        }
        if (!in.extraInstructions().isBlank()) {
            user.append("Instructions from the creator: ").append(in.extraInstructions()).append('\n');
        }
        return call(settings.writerModel(), settings.writerEffort(), writerSystem, creatorProfile(), user.toString(),
                DraftText.class, 4000, false);
    }

    @Override
    public BrandLeads findBrands(BrandSearchInput in) {
        StringBuilder user = new StringBuilder()
                .append("Find up to ").append(in.count()).append(" brands for this request: ")
                .append(Untrusted.escape(in.query())).append('\n');
        if (!in.excludeBrands().isEmpty()) {
            user.append("\nAlready in the creator's CRM, don't return these: ")
                    .append(String.join(", ", in.excludeBrands())).append('\n');
        }
        return call(settings.writerModel(), settings.writerEffort(), researchSystem, creatorProfile(), user.toString(),
                BrandLeads.class, 16000, true);
    }

    private String creatorProfile() {
        StringBuilder profile = new StringBuilder("Creator name: ").append(settings.creatorName())
                .append("\n\n# Creator profile\n").append(settings.creatorProfile());
        for (String section : List.of(links.profileSection(), instagramStats.profileSection())) {
            if (!section.isEmpty()) profile.append("\n\n").append(section);
        }
        return profile.toString();
    }

    /** Server-side tool loops pause after a while; resume a few times before giving up. */
    private static final int MAX_CONTINUATIONS = 4;

    private <T> T call(String model, String effort, String system, String system2, String user,
                       Class<T> type, long maxTokens, boolean webSearch) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        OutputSchemas.of(type).forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
        OutputConfig.Builder out = OutputConfig.builder()
                .format(JsonOutputFormat.builder().schema(schema.build()).build());
        if (supportsEffort(model)) out.effort(OutputConfig.Effort.of(effort));

        List<TextBlockParam> systemBlocks = new ArrayList<>();
        systemBlocks.add(TextBlockParam.builder().text(system).build());
        if (system2 != null) systemBlocks.add(TextBlockParam.builder().text(system2).build());
        // Cache the stable system prefix; the per-message content comes after it.
        TextBlockParam last = systemBlocks.remove(systemBlocks.size() - 1);
        systemBlocks.add(last.toBuilder().cacheControl(CacheControlEphemeral.builder().build()).build());

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .outputConfig(out.build())
                .systemOfTextBlockParams(systemBlocks)
                .addUserMessage(user);
        if (webSearch) params.addTool(WebSearchTool20260209.builder().maxUses(15L).build());
        if (supportsDefaultFallback(model)) {
            // On a safety-classifier decline, let the API retry on its recommended fallback model.
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        Message response = create(params);
        for (int i = 0; i < MAX_CONTINUATIONS && StopReason.PAUSE_TURN.equals(response.stopReason().orElse(null)); i++) {
            // Send the paused turn back as-is; the API resumes the search where it left off.
            params.addMessage(response);
            response = create(params);
        }
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.PAUSE_TURN.equals(stop)) throw new LlmException("Claude's web research didn't finish; try a narrower search");
        if (StopReason.REFUSAL.equals(stop)) throw new LlmException("Claude declined this request");
        if (StopReason.MAX_TOKENS.equals(stop)) throw new LlmException("Claude response was cut off (max tokens)");
        // With web search the reply interleaves search blocks; the structured answer is the last text block.
        String json = response.content().stream()
                .flatMap(b -> b.text().stream())
                .map(t -> t.text())
                .reduce((a, b) -> b)
                .orElseThrow(() -> new LlmException("Claude returned no structured output"));
        try {
            return OutputSchemas.parse(json, type);
        } catch (RuntimeException e) {
            throw new LlmException("Claude's reply didn't match the expected format: " + firstLine(e), e);
        }
    }

    private Message create(MessageCreateParams.Builder params) {
        try {
            return client().messages().create(params.build());
        } catch (AnthropicServiceException e) {
            throw new LlmException("Claude API error (" + e.statusCode() + "): " + firstLine(e), e);
        } catch (AnthropicException e) {
            // Network trouble, timeouts, a response we couldn't read: not an HTTP error, but just as fatal.
            throw new LlmException("Claude request failed: " + e.getClass().getSimpleName() + ": " + firstLine(e), e);
        }
    }

    /** For tests: send requests to a local stub instead of the real API. */
    synchronized void useBaseUrl(String url) {
        baseUrl = url;
        clientKeyHash = null; // rebuild the client on next use
    }

    private static String firstLine(Exception e) {
        String m = String.valueOf(e.getMessage()).strip();
        m = m.lines().findFirst().orElse(m);
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }

    private synchronized AnthropicClient client() {
        String key = secrets.require(SecretName.ANTHROPIC_API_KEY);
        String hash = CryptoService.sha256Hex(key);
        if (client == null || !hash.equals(clientKeyHash)) {
            if (client != null) client.close();
            AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(key).maxRetries(3);
            if (baseUrl != null) b.baseUrl(baseUrl);
            client = b.build();
            clientKeyHash = hash;
        }
        return client;
    }

    private static boolean supportsEffort(String model) {
        return !model.startsWith("claude-haiku") && !model.startsWith("claude-sonnet-4-5");
    }

    private static boolean supportsDefaultFallback(String model) {
        return model.equals("claude-opus-5-5") || model.equals("claude-opus-5")
                || model.equals("claude-sonnet-5-5") || model.equals("claude-fable-5-1");
    }

    private static String resource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
