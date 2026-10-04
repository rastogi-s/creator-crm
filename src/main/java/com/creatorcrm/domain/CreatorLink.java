package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** One of the creator's own links (social profile, website, portfolio, media kit...). */
@Entity
@Table(name = "creator_links")
public class CreatorLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String label;
    public String url;
    public int sortOrder;
    public OffsetDateTime createdAt;
}
