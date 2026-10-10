package com.creatorcrm.llm;

import java.time.LocalDate;
import java.util.List;

/** A brand's newly found posts, emails and articles for Claude to sort into news worth a re-pitch. */
public record NewsSortInput(LocalDate today, String brandName, String lastCollab, List<Item> items) {

    /** {@code text} is third-party content: an email, a caption, a feed summary or a headline. */
    public record Item(long id, String source, String date, String title, String text) {}
}
