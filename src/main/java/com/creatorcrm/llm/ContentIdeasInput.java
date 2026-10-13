package com.creatorcrm.llm;

import java.time.LocalDate;
import java.util.List;

/**
 * What Claude is given to plan content for a deal. {@code guidelines} is already wrapped as untrusted data;
 * {@code dealContext}, {@code profile} and the post lists are rendered from the app's own records. {@code files} are
 * the brief's PDFs and pictures, which Claude reads as documents.
 */
public record ContentIdeasInput(
        LocalDate today,
        String brandName,
        String dealContext,
        String profile,
        List<String> recentPosts,
        List<String> pastBrandPosts,
        String guidelines,
        String note,
        List<BriefFile> files) {

    public ContentIdeasInput {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
