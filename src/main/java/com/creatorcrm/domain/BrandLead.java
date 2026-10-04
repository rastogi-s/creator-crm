package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A brand suggested by web research, waiting for the creator to pitch it or dismiss it. */
@Entity
@Table(name = "brand_leads")
public class BrandLead {
    public enum Status { NEW, DRAFTED, DISMISSED }

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
}
