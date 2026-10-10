package com.creatorcrm.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** Launches and teasers found by web search for one brand. */
public record NewsFound(
        @JsonPropertyDescription("News items found, newest first. Empty if nothing recent was found.")
        List<Item> items) {

    public record Item(
            @JsonPropertyDescription("The headline or a one-line description of the news, in plain words.")
            String title,
            @JsonPropertyDescription("URL of the page where you read it. Required: never return an item without one.")
            String url,
            @JsonPropertyDescription("The date the page gives for the news, as YYYY-MM-DD. Empty if the page gives none.")
            String date,
            @JsonPropertyDescription("Two or three sentences of what the page says: the product, what is new, any launch date.")
            String summary) {}
}
