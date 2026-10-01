package com.creatorcrm.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record DraftText(
        @JsonPropertyDescription("Email subject. For replies keep the existing subject. Empty for Instagram DMs.")
        String subject,
        @JsonPropertyDescription("The message body in the creator's voice, ready to send.")
        String body) {}
