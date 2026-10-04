package com.creatorcrm.llm;

import java.time.LocalDate;
import java.util.List;

public record DraftInput(
        LocalDate today,
        String draftType,
        String platform,
        String brandName,
        String contactName,
        String opportunityContext,
        String conversationSummary,
        List<String> recentMessages,
        String extraInstructions,
        /** Messages the creator sent before, rendered for the prompt (see LearningService). May be empty. */
        List<String> pastExamples) {}
