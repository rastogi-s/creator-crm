package com.creatorcrm.llm;

/**
 * How hard "Find brands" researches. Every web search costs a cent and the pages it reads are billed as input, so
 * the search cap is what sets the price. {@code roughUsd} is the starting guess shown before any real runs.
 */
public enum SearchDepth {
    QUICK(3, 0.25),
    STANDARD(6, 0.45),
    THOROUGH(15, 1.00);

    public final int maxSearches;
    public final double roughUsd;

    SearchDepth(int maxSearches, double roughUsd) {
        this.maxSearches = maxSearches;
        this.roughUsd = roughUsd;
    }

    public static SearchDepth parse(String s) {
        if (s == null || s.isBlank()) return STANDARD;
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Search depth must be QUICK, STANDARD or THOROUGH");
        }
    }
}
