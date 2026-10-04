package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** An Instagram account that tagged, @mentioned or commented on the creator. Possible brand leads. */
@Entity
@Table(name = "instagram_engagements")
public class InstagramEngagement {
    public enum Kind { TAG, MENTION, COMMENT }

    public enum Status { NEW, LEAD, DISMISSED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String username;
    public int mentions;
    public int comments;
    @Enumerated(EnumType.STRING) public Kind lastKind;
    public String lastText;
    public String lastPermalink;
    public OffsetDateTime lastSeenAt;
    @Enumerated(EnumType.STRING) public Status status;
    /** True: a business or creator account. False: a personal account (hidden). Null: not checked yet. */
    public Boolean isBusiness;
    public Long followers;
    public OffsetDateTime checkedAt;
}
