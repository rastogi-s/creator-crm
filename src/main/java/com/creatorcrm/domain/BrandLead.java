package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * A brand suggested by web research, by its engagement with the creator on Instagram, or looked up by handle,
 * waiting for the creator to pitch it or dismiss it.
 */
@Entity
@Table(name = "brand_leads")
public class BrandLead {
    public enum Status { NEW, DRAFTED, DISMISSED }

    /**
     * WEB: Find brands research. INSTAGRAM: tagged, mentioned or commented on her. LOOKUP: she looked up a handle.
     * CATEGORY: the free category search (Wikidata open data, no Claude).
     */
    public enum Source { WEB, INSTAGRAM, LOOKUP, CATEGORY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    public String nameKey;
    public String website;
    public String instagram;
    public String contactEmail;
    public String contactSourceUrl;
    public String fitReason;
    public String pitchAngle;
    public String searchQuery;
    @Enumerated(EnumType.STRING) public Status status;
    public Long opportunityId;
    public OffsetDateTime createdAt;
    @Enumerated(EnumType.STRING) public Source source = Source.WEB;
    /** From the brand's Instagram account (needs the Facebook connection); null until looked up. */
    public Long igFollowers;
    public String igBio;
    /** Creators the brand tagged in sponsored-looking posts, comma separated, without @. */
    public String igPartners;
    public OffsetDateTime igCheckedAt;
    /** Last time the website was read for contact addresses; null = never. */
    public OffsetDateTime websiteCheckedAt;
}
