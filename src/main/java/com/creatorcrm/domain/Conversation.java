package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.Platform;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "conversations")
public class Conversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long brandId;
    @Enumerated(EnumType.STRING) public Platform platform;
    public String externalId;
    public String counterparty;
    public String subject;
    public String summary;
    public Boolean brandRelated;
    public OffsetDateTime createdAt;
    public OffsetDateTime lastMessageAt;
    /** Her own call: {@link #LEAD} or {@link #NOT_LEAD}; null = go by Claude's read ({@link #brandRelated}). */
    public String leadTag;
    /** The unanswered message a DM reply reminder was last made for. */
    public Long remindedMessageId;

    public static final String LEAD = "LEAD";
    public static final String NOT_LEAD = "NOT_LEAD";

    /** A lead because she said so, or because Claude saw a brand and she didn't say otherwise. */
    public boolean isLead() {
        return leadTag == null ? Boolean.TRUE.equals(brandRelated) : LEAD.equals(leadTag);
    }
}
