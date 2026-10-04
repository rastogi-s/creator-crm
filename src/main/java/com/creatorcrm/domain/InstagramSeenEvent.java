package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A comment or tag already counted in {@link InstagramEngagement}. */
@Entity
@Table(name = "instagram_seen_events")
public class InstagramSeenEvent {
    @Id public String externalId;
    public OffsetDateTime seenAt;
}
