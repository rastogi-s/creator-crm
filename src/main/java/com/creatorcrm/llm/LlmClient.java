package com.creatorcrm.llm;

/**
 * Provider-neutral boundary: the rest of the app only talks to this interface, so another provider can
 * be added as a second implementation and compared on the same messages.
 */
public interface LlmClient {

    /** Stage 2/3: decide what a message means. Never decides workflow; that's the WorkflowEngine's job. */
    MessageAnalysis classify(ClassificationInput input);

    /** Write a message in the creator's voice. Always goes to the approval queue, never sent directly. */
    DraftText writeDraft(DraftInput input);

    boolean isConfigured();
}
