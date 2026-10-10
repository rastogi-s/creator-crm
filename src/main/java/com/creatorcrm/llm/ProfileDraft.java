package com.creatorcrm.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** A suggested About you profile. She reviews and edits it; nothing is saved until she presses Save. */
public record ProfileDraft(
        @JsonPropertyDescription("The full profile in Markdown, with the sections About me, My voice, Rates and Rules.")
        String profile,
        @JsonPropertyDescription("Short plain sentences, one per item, naming what the creator should check or fill in "
                + "herself (for example a rate left as ___, or a guess about her audience). Empty list when nothing.")
        List<String> toCheck) {}
