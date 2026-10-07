package com.creatorcrm.llm;

import java.util.List;
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

    /**
     * Rewrite an existing draft the way the creator asks ("warmer", "shorter"). {@code context} is the same deal
     * context a new draft gets. The result replaces the draft's text; it is never sent directly.
     */
    DraftText reviseDraft(DraftInput context, DraftText current, String request);

    /** Research brands on the web that could be pitched. Results are suggestions the creator reviews. */
    BrandLeads findBrands(BrandSearchInput input);

    /** Read a brand contract's terms. The app's own rules then check them against the creator's limits. */
    ContractTerms extractContractTerms(ContractInput input);

    /** What Claude read off a picture, and what that cost in USD. */
    record PictureContacts(List<ContactCards.Card> contacts, double costUsd) {}

    /**
     * Read the contacts in a picture (PNG, JPEG, GIF or WebP). Only when she asks for it: it costs about a cent, and
     * the app tries the computer's own text reader first.
     */
    default PictureContacts readContactPicture(byte[] image, String mediaType) {
        throw new UnsupportedOperationException("Reading pictures isn't supported");
    }

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

    /**
     * Pitch campaigns' Personalise: one opening line per brand from Claude Haiku, in a half-price batch. Keys are
     * caller ids echoed back by {@link #pollOpeningLines}.
     */
    default String submitOpeningLines(Map<String, OpeningLineInput> inputs) {
        throw new UnsupportedOperationException("Personalised opening lines aren't supported");
    }

    /** {@code null} while the batch is still running; otherwise the lines by caller id. Missing ids failed. */
    default Map<String, String> pollOpeningLines(String batchId) {
        throw new UnsupportedOperationException("Personalised opening lines aren't supported");
    }
}
