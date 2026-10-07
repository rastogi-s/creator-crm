package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A web domain a brand owns, e.g. glowberry.com. */
@Entity
@Table(name = "brand_domains")
public class BrandDomain {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long brandId;
    public String domain;
    /** Last time the brand's website was read for contacts; null = never. */
    public OffsetDateTime crawledAt;
}
