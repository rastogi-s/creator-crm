package com.creatorcrm.domain;

/** A file sent with an email draft (today: an invoice PDF). Built when needed, never stored. */
public record Attachment(String filename, String mimeType, byte[] content) {}
