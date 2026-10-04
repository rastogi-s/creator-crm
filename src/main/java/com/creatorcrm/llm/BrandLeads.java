package com.creatorcrm.llm;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** Brands found by web research that could be a fit for the creator. */
public record BrandLeads(
        @JsonPropertyDescription("Brands that fit the request, best fit first.")
        List<Lead> leads) {

    public record Lead(
            @JsonPropertyDescription("The brand's name as it presents itself.")
            String name,
            @JsonPropertyDescription("The brand's official website URL, or empty if not found.")
            String website,
            @JsonPropertyDescription("The brand's Instagram handle without @, or empty if not found.")
            String instagram,
            @JsonPropertyDescription("A partnerships, PR, influencer or general contact email the brand publishes itself. "
                    + "Empty if none was found on a page. Never guess or construct an address.")
            String contactEmail,
            @JsonPropertyDescription("URL of the page where the contact email was found, or empty.")
            String contactSourceUrl,
            @JsonPropertyDescription("One or two sentences on why this brand fits the creator, citing what you found "
                    + "(e.g. they work with similar creators, run a creator program, launched a fitting product).")
            String fitReason,
            @JsonPropertyDescription("A specific collaboration idea the creator could pitch to this brand.")
            String pitchAngle) {}
}
