package com.creatorcrm.llm;

import java.util.List;

/** What kind of brands to look for, and brands already in the CRM that shouldn't come back. */
public record BrandSearchInput(String query, int count, List<String> excludeBrands) {}
