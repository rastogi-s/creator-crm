package com.creatorcrm.llm;

import java.time.LocalDate;

/** A brand to look up recent launches and teasers for, when its own feeds and posts turned up nothing. */
public record NewsSearchInput(LocalDate today, String brandName, String website, String instagram, int days, SearchDepth depth) {}
