package com.creatorcrm.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** The people Claude read off a picture (a business card, a screenshot of a list or a bio). */
public record ContactCards(
        @JsonPropertyDescription("Every person or inbox in the picture that has an email address, in the order they appear.")
        List<Card> contacts) {

    public record Card(
            @JsonPropertyDescription("Email address exactly as written.") String email,
            @JsonPropertyDescription("The person's full name, or empty for a shared inbox.") String name,
            @JsonPropertyDescription("Job title, or empty.") String title,
            @JsonPropertyDescription("Company or brand name, or empty.") String brand,
            @JsonPropertyDescription("Company website, or empty.") String website,
            @JsonPropertyDescription("Phone number as written, or empty.") String phone,
            @JsonPropertyDescription("Instagram handle, or empty.") String instagram,
            @JsonPropertyDescription("LinkedIn profile URL, or empty.") String linkedin) {}
}
