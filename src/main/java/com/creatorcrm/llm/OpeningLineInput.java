package com.creatorcrm.llm;

/**
 * What Claude Haiku gets to write one personal opening line for a campaign pitch. Everything but the brand name
 * may come from the web or the brand's Instagram, so it is treated as untrusted text.
 */
public record OpeningLineInput(String brand, String fitReason, String pitchIdea, String instagramBio) {}
