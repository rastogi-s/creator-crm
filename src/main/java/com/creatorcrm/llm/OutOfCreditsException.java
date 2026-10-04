package com.creatorcrm.llm;

/** The Claude account has no credits left: every call will fail the same way until someone tops up. */
public class OutOfCreditsException extends LlmException {
    public OutOfCreditsException(Throwable cause) {
        super("Claude credits have run out. Add credits in the Claude Console.", cause);
    }
}
