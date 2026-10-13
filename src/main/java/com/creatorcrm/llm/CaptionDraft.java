package com.creatorcrm.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** One post's captions for YouTube, Instagram and TikTok, without any links: the app adds those from her products. */
public record CaptionDraft(
        @JsonPropertyDescription("A short name for this post in her list, max 6 words, e.g. 'Morning skincare reset'.")
        String title,
        @JsonPropertyDescription("The numbers of the products from the list that are in this post, in the order they appear. Empty if none fit.")
        List<Integer> productNumbers,
        @JsonPropertyDescription("One easy word in capitals that people comment to get the links by DM, about this post, e.g. SERUM or COZY. Letters only, max 12.")
        String keyword,
        @JsonPropertyDescription("3 different on-screen text hooks for the first second of the video, max 10 words each.")
        List<String> hooks,
        YouTube youtube,
        Instagram instagram,
        TikTok tiktok,
        @JsonPropertyDescription("The DM sent to someone who comments the keyword or asks for the link: 1-2 warm sentences in her voice. No links or codes: the app adds the products, links and codes under it.")
        String dmReply) {

    public record YouTube(
            @JsonPropertyDescription("The video title, max 90 characters, searchable and curiosity-making. Empty if YouTube isn't asked for.")
            String title,
            @JsonPropertyDescription("The description: a first line that hooks in search, then 2-5 short lines about the video in her voice. No links, no hashtags, no product list: the app adds 'Shop this video' with the links after it. Empty if YouTube isn't asked for.")
            String description,
            @JsonPropertyDescription("8-15 search tags, lower case, no # sign. Empty if YouTube isn't asked for.")
            List<String> tags,
            @JsonPropertyDescription("A comment for her to pin: one or two friendly sentences that invite people to shop or ask questions. No links. Empty if YouTube isn't asked for.")
            String pinnedComment) {}

    public record Instagram(
            @JsonPropertyDescription("The Reel caption: a first line that stops the scroll, a few short lines in her voice, then a call to action that says 'Comment KEYWORD' (with the keyword) to get the links by DM when there are products. No hashtags, no links. Empty if Instagram isn't asked for.")
            String caption,
            @JsonPropertyDescription("3-8 hashtags that fit this post, each starting with #. Empty if Instagram isn't asked for.")
            List<String> hashtags,
            @JsonPropertyDescription("Text for a Story about this post, max 12 words, pointing to the link sticker when there are products. Empty if Instagram isn't asked for.")
            String storyText) {}

    public record TikTok(
            @JsonPropertyDescription("The TikTok caption: 1-3 short lines in her voice. When there are products, say the links are in her bio (or to comment the keyword). No hashtags, no links. Empty if TikTok isn't asked for.")
            String caption,
            @JsonPropertyDescription("3-6 hashtags that fit, each starting with #. Empty if TikTok isn't asked for.")
            List<String> hashtags) {}
}
