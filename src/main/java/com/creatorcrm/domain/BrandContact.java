package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A person (or role inbox) at a brand. A brand can have many; the email is unique across all brands. */
@Entity
@Table(name = "brand_contacts")
public class BrandContact {
    public enum Role { PARTNERSHIPS, PR, MARKETING, FOUNDER, GENERAL, SUPPORT, OTHER }
    public enum Verified { VALID, RISKY, INVALID, UNKNOWN }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long brandId;
    public String email;
    public String name;
    public String title;
    @Enumerated(EnumType.STRING) public Role role = Role.OTHER;
    public String phone;
    public String linkedin;
    public String instagram;
    @Enumerated(EnumType.STRING) public Verified verified = Verified.UNKNOWN;
    public OffsetDateTime verifiedAt;
    public Integer confidence;
    public int emailsSent;
    public int replies;
    public int dealsWon;
    public Integer avgReplyHours;
    public OffsetDateTime lastContactedAt;
    public OffsetDateTime lastRepliedAt;
    public boolean bounced;
    public boolean optedOut;
    /** Ranking score, 0-100; higher = better person to pitch at this brand. See {@code ContactRanking}. */
    public int score;
    public String scoreReason;
    public String notes;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;

    /** False when the app must never email this address. */
    public boolean emailable() {
        return !bounced && !optedOut && verified != Verified.INVALID;
    }
}
