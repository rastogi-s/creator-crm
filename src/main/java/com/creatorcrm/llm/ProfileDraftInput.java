package com.creatorcrm.llm;

import java.util.List;

/**
 * What the app knows about the creator, for drafting her About you profile. {@code deals} and {@code sentMessages}
 * are already rendered for the prompt; {@code fetchDomains} are the hosts of her own links Claude may open.
 */
public record ProfileDraftInput(
        String creatorName,
        String currentProfile,
        List<String> links,
        String instagramStats,
        List<String> deals,
        List<String> sentMessages,
        List<String> fetchDomains) {}
