package com.creatorcrm.llm;

import java.time.LocalDate;
import java.util.List;

/**
 * What Claude is given to write one post's captions. {@code about} and {@code note} are her own words; {@code products}
 * are numbered lines from her product list (names only, never links); {@code pick}: the products are her whole list to
 * choose from, not the ones she said are in the video. {@code deal} is rendered from the app's records (blank when the
 * post isn't for a deal). {@code pictures} are screenshots from the video.
 */
public record CaptionInput(
        LocalDate today,
        String about,
        String note,
        List<String> products,
        boolean pick,
        String deal,
        String youtubeFormat,
        List<String> platforms,
        List<String> recentCaptions,
        List<BriefFile> pictures) {

    public CaptionInput {
        products = products == null ? List.of() : List.copyOf(products);
        platforms = platforms == null ? List.of() : List.copyOf(platforms);
        recentCaptions = recentCaptions == null ? List.of() : List.copyOf(recentCaptions);
        pictures = pictures == null ? List.of() : List.copyOf(pictures);
    }
}
