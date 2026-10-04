package com.creatorcrm.llm;

import java.util.Map;

/**
 * Provider-neutral boundary: the rest of the app only talks to this interface, so another provider can
 * be added as a second implementation and compared on the same messages.
 */
public interface LlmClient {

    /** Stage 2/3: decide what a message means. Never decides workflow; that's the WorkflowEngine's job. */
    MessageAnalysis classify(ClassificationInput input);

    /** Write a message in the creator's voice. Always goes to the approval queue, never sent directly. */
    DraftText writeDraft(DraftInput input);

    /** Research brands on the web that could be pitched. Results are suggestions the creator reviews. */
    BrandLeads findBrands(BrandSearchInput input);

    boolean isConfigured();

    /** Whether {@link #submitClassifyBatch} works: classification at half price, results within hours. */
    default boolean supportsBatch() { return false; }

    /** Queue classifications to run in the background. Keys are caller ids echoed back by {@link #pollClassifyBatch}. */
    default String submitClassifyBatch(Map<String, ClassificationInput> inputs) {
        throw new UnsupportedOperationException("Batch classification isn't supported");
    }

    /**
     * {@code null} while the batch is still running; otherwise the analyses by caller id. Ids missing from the map
     * failed and should be classified again.
     */
    default Map<String, MessageAnalysis> pollClassifyBatch(String batchId) {
        throw new UnsupportedOperationException("Batch classification isn't supported");
    }
}
