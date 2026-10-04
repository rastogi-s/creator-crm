package com.creatorcrm.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.ErrorType;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.core.http.StreamResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final ClaudeSpend spend;
    private final String classifierSystem;
    private final String writerSystem;
    private final String researchSystem;

    private AnthropicClient client;
    private String clientKeyHash;
    private String baseUrl; // null = the real API; tests point it at a local stub

    public ClaudeLlmClient(SecretStore secrets, SettingsService settings, LinkService links,
                           InstagramStatsService instagramStats, ClaudeSpend spend) {
        this.secrets = secrets;
        this.settings = settings;
        this.links = links;
        this.instagramStats = instagramStats;
        this.spend = spend;
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
        MessageAnalysis a = call(ClaudeSpend.Feature.CLASSIFY, settings.classifierModel(), settings.classifierEffort(),
                classifierSystem, null, classifyPrompt(in), MessageAnalysis.class, 4000, null);
        return AnalysisValidator.sanitize(a);
    }

    private static String classifyPrompt(ClassificationInput in) {
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
        return user.toString();
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public String submitClassifyBatch(Map<String, ClassificationInput> inputs) {
        String model = settings.classifierModel();
        OutputConfig out = outputConfig(model, settings.classifierEffort(), MessageAnalysis.class);
        List<TextBlockParam> system = systemBlocks(classifierSystem, null);
        BatchCreateParams.Builder batch = BatchCreateParams.builder();
        inputs.forEach((id, in) -> batch.addRequest(BatchCreateParams.Request.builder()
                .customId(id)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(model)
                        .maxTokens(4000L)
                        .outputConfig(out)
                        .systemOfTextBlockParams(system)
                        .addUserMessage(classifyPrompt(in))
                        .build())
                .build()));
        return api(() -> client().messages().batches().create(batch.build())).id();
    }

    @Override
    public Map<String, MessageAnalysis> pollClassifyBatch(String batchId) {
        MessageBatch batch = api(() -> client().messages().batches().retrieve(batchId));
        if (!MessageBatch.ProcessingStatus.ENDED.equals(batch.processingStatus())) return null;
        Map<String, MessageAnalysis> results = new LinkedHashMap<>();
        try (StreamResponse<MessageBatchIndividualResponse> stream =
                     api(() -> client().messages().batches().resultsStreaming(batchId))) {
            stream.stream().forEach(r -> r.result().succeeded().ifPresent(ok -> {
                Message m = ok.message();
                spend.record(ClaudeSpend.Feature.CLASSIFY, m, BATCH_DISCOUNT);
                // Refused, cut off or unreadable: leave it out, and it's classified again the normal way.
                if (!StopReason.END_TURN.equals(m.stopReason().orElse(null))) return;
                m.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).reduce((a, b) -> b)
                        .ifPresent(json -> {
                            try {
                                results.put(r.customId(), AnalysisValidator.sanitize(
                                        OutputSchemas.parse(json, MessageAnalysis.class)));
                            } catch (RuntimeException e) {
                                // as above
                            }
                        });
            }));
        }
        return results;
    }

    /** Batch requests are billed at half the normal price. */
    static final double BATCH_DISCOUNT = 0.5;

    @Override
    public DraftText writeDraft(DraftInput in) {
        StringBuilder user = new StringBuilder()
                .append("Today: ").append(in.today()).append('\n')
                .append("Write a ").append(in.draftType()).append(" message via ").append(in.platform())
                .append(" to ").append(in.brandName())
                .append(in.contactName().isBlank() ? "" : " (contact: " + in.contactName() + ")").append(".\n\n");
        appendDraftContext(user, in);
        if (!in.extraInstructions().isBlank()) {
            user.append("Instructions from the creator: ").append(in.extraInstructions()).append('\n');
        }
        return call(ClaudeSpend.Feature.DRAFT, settings.writerModel(), settings.writerEffort(), writerSystem, creatorProfile(), user.toString(),
                DraftText.class, 4000, null);
    }

    @Override
    public DraftText reviseDraft(DraftInput in, DraftText current, String request) {
        StringBuilder user = new StringBuilder()
                .append("Today: ").append(in.today()).append('\n')
                .append("The creator wants to change a ").append(in.draftType()).append(" message via ").append(in.platform())
                .append(" to ").append(in.brandName())
                .append(in.contactName().isBlank() ? "" : " (contact: " + in.contactName() + ")").append(".\n\n");
        appendDraftContext(user, in);
        user.append("Current draft:\n<current_draft>\n");
        if (!current.subject().isBlank()) user.append("Subject: ").append(current.subject()).append('\n');
        user.append(current.body()).append("\n</current_draft>\n\n")
                .append("Rewrite the current draft as the creator asks below and return the whole new version. Change only ")
                .append("what the request is about: keep the facts, numbers, dates, links, placeholders and anything already ")
                .append("agreed. Don't add rates, links or terms that aren't in the profile, the deal record or the draft; use ")
                .append("a placeholder instead. Keep the subject unless the request is about it.\n\n")
                .append("The creator's request: ").append(request).append('\n');
        return call(ClaudeSpend.Feature.REVISE, settings.writerModel(), settings.writerEffort(), writerSystem, creatorProfile(), user.toString(),
                DraftText.class, 4000, null);
    }

    /** The deal, conversation and past examples a writer needs, shared by new drafts and rewrites. */
    private static void appendDraftContext(StringBuilder user, DraftInput in) {
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
    }

    @Override
    public BrandLeads findBrands(BrandSearchInput in) {
        StringBuilder user = new StringBuilder()
                .append("Find up to ").append(in.count()).append(" brands for this request: ")
                .append(Untrusted.escape(in.query())).append('\n');
        user.append("You can run at most ").append(in.depth().maxSearches)
                .append(" web searches, so choose them carefully.\n");
        if (!in.excludeBrands().isEmpty()) {
            user.append("\nAlready in the creator's CRM, don't return these: ")
                    .append(String.join(", ", in.excludeBrands())).append('\n');
        }
        return call(ClaudeSpend.Feature.RESEARCH, settings.writerModel(), settings.writerEffort(), researchSystem, creatorProfile(), user.toString(),
                BrandLeads.class, 16000, in.depth());
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

    private <T> T call(ClaudeSpend.Feature feature, String model, String effort, String system, String system2, String user,
                       Class<T> type, long maxTokens, SearchDepth webSearch) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .outputConfig(outputConfig(model, effort, type))
                .systemOfTextBlockParams(systemBlocks(system, system2))
                .addUserMessage(user);
        if (webSearch != null) params.addTool(WebSearchTool20260209.builder().maxUses((long) webSearch.maxSearches).build());
        if (supportsDefaultFallback(model)) {
            // On a safety-classifier decline, let the API retry on its recommended fallback model.
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        Message response = create(params);
        double runUsd = spend.record(feature, response);
        for (int i = 0; i < MAX_CONTINUATIONS && StopReason.PAUSE_TURN.equals(response.stopReason().orElse(null)); i++) {
            // Send the paused turn back as-is; the API resumes the search where it left off.
            params.addMessage(response);
            response = create(params);
            runUsd += spend.record(feature, response);
        }
        if (webSearch != null) spend.recordResearchRun(webSearch, runUsd);
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

    private static OutputConfig outputConfig(String model, String effort, Class<?> type) {
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        OutputSchemas.of(type).forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
        OutputConfig.Builder out = OutputConfig.builder()
                .format(JsonOutputFormat.builder().schema(schema.build()).build());
        if (supportsEffort(model)) out.effort(OutputConfig.Effort.of(effort));
        return out.build();
    }

    private static List<TextBlockParam> systemBlocks(String system, String system2) {
        List<TextBlockParam> blocks = new ArrayList<>();
        blocks.add(TextBlockParam.builder().text(system).build());
        if (system2 != null) blocks.add(TextBlockParam.builder().text(system2).build());
        // Cache the stable system prefix; the per-message content comes after it.
        TextBlockParam last = blocks.remove(blocks.size() - 1);
        blocks.add(last.toBuilder().cacheControl(CacheControlEphemeral.builder().build()).build());
        return blocks;
    }

    private Message create(MessageCreateParams.Builder params) {
        return api(() -> client().messages().create(params.build()));
    }

    /** One API call, with its errors turned into readable {@link LlmException}s. */
    private <R> R api(java.util.function.Supplier<R> request) {
        try {
            return request.get();
        } catch (AnthropicServiceException e) {
            if (isOutOfCredits(e)) {
                spend.markOutOfCredits(firstLine(e));
                throw new OutOfCreditsException(e);
            }
            throw new LlmException("Claude API error (" + e.statusCode() + "): " + firstLine(e), e);
        } catch (AnthropicException e) {
            // Network trouble, timeouts, a response we couldn't read: not an HTTP error, but just as fatal.
            throw new LlmException("Claude request failed: " + e.getClass().getSimpleName() + ": " + firstLine(e), e);
        }
    }

    /**
     * The API's billing error (402), or the 400 it sends when the prepaid balance is used up
     * ("Your credit balance is too low to access the Anthropic API").
     */
    static boolean isOutOfCredits(AnthropicServiceException e) {
        if (e.statusCode() == 402 || e.errorType().filter(ErrorType.BILLING_ERROR::equals).isPresent()) return true;
        return e.statusCode() == 400 && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("credit balance");
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
