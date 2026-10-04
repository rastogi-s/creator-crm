package com.creatorcrm.llm;

import java.util.List;

/** What kind of brands to look for, brands already in the CRM that shouldn't come back, and how hard to look. */
public record BrandSearchInput(String query, int count, List<String> excludeBrands, SearchDepth depth) {

    public BrandSearchInput(String query, int count, List<String> excludeBrands) {
        this(query, count, excludeBrands, SearchDepth.STANDARD);
    }
}
