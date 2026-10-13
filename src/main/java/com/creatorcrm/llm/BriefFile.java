package com.creatorcrm.llm;

/**
 * A file from a brand's brief that Claude looks at directly, not as extracted text: a PDF (so the pictures and layout
 * of a design-heavy deck come through) or a picture. {@code mediaType} is application/pdf or an image type.
 */
public record BriefFile(String name, String mediaType, byte[] data) {}
