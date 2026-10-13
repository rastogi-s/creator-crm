package com.creatorcrm.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** Reel and video ideas for one deal, written from the brand's guidelines and what the creator's audience likes. */
public record ContentIdeaList(
        @JsonPropertyDescription("2-3 plain sentences: what the brand wants from the content (product, deliverables, key message, deadline). Only from the guidelines and deal details given.")
        String briefSummary,
        @JsonPropertyDescription("Every must-do in the guidelines as a short checklist line: things to say or show, tags, handles, hashtags, links, discount codes, ad disclosure, length, format, posting date. Copy handles, hashtags and codes exactly. Empty list if the guidelines name none.")
        List<String> mustInclude,
        @JsonPropertyDescription("Every don't in the guidelines as a short line: words, claims, competitors, music or visuals to avoid. Empty list if none.")
        List<String> avoid,
        @JsonPropertyDescription("4 to 6 different ideas, best first. Each one must follow every must-include and avoid line.")
        List<Idea> ideas) {

    public record Idea(
            @JsonPropertyDescription("A short catchy name for the idea, max 8 words.")
            String title,
            @JsonPropertyDescription("The deliverable it is for, as the deal names it: e.g. 'Instagram Reel', 'TikTok', 'Story set', 'Carousel', 'UGC video'.")
            String format,
            @JsonPropertyDescription("The first 1-3 seconds: the exact opening line on screen or spoken, written to stop the scroll.")
            String hook,
            @JsonPropertyDescription("2-3 sentences: what happens in the video and how the product comes in.")
            String concept,
            @JsonPropertyDescription("3-7 shots or beats in order, each a short line she can film from, e.g. 'Close-up: pour the tea, steam rising'.")
            List<String> shots,
            @JsonPropertyDescription("A ready-to-use caption in her voice, with the required tags, hashtags and disclosure.")
            String caption,
            @JsonPropertyDescription("One sentence on why this suits her and her audience, pointing at the past post or theme it builds on when one was given.")
            String whyItFits) {}
}
