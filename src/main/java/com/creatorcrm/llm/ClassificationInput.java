package com.creatorcrm.llm;

import java.time.LocalDate;
import java.util.List;

/**
 * Context for classifying one new message. We send the rolling conversation summary plus the last few
 * messages instead of the whole thread to keep token usage low.
 */
public record ClassificationInput(
        LocalDate today,
        String platform,
        String direction,
        String opportunityContext,
        String conversationSummary,
        List<String> recentMessages,
        String newMessage) {}
