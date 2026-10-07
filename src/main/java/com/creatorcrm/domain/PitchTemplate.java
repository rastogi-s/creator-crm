package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** Pitch text with merge fields such as {first_name} or {why_you|fallback text}. See {@code MergeFields}. */
@Entity
@Table(name = "pitch_templates")
public class PitchTemplate {
    public enum Kind { COLLAB, UGC, GIFTING, RATES, OTHER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    @Enumerated(EnumType.STRING) public Kind kind = Kind.COLLAB;
    public String subject;
    public String body;
    /** Used for every follow-up of a pitch from this template, so follow-ups need no Claude either. */
    public String followUpBody;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
