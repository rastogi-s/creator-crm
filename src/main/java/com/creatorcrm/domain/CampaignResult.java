package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** The post a deal produced and how it did: fetched from Instagram a week after posting, or typed in by the creator. */
@Entity
@Table(name = "campaign_results")
public class CampaignResult {
    public enum Source { INSTAGRAM, MANUAL }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    public String postUrl;
    /** The Instagram media id, when the post is hers on the connected account. */
    public String mediaId;
    public String mediaType;
    public String caption;
    public OffsetDateTime postedAt;
    @Enumerated(EnumType.STRING) public Source source;
    public Long reach;
    public Long views;
    public Long likes;
    public Long comments;
    public Long saves;
    public Long shares;
    public OffsetDateTime fetchedAt;
    public String error;
    public OffsetDateTime recapDraftedAt;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    public boolean hasNumbers() {
        return reach != null || views != null || likes != null || comments != null || saves != null || shares != null;
    }
}
