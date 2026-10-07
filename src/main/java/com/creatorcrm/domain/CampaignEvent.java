package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A bounce, opt-out or reply seen for a campaign email. Counted for the auto-pause and the warm-up. */
@Entity
@Table(name = "campaign_events")
public class CampaignEvent {
    public enum Kind { BOUNCE, OPT_OUT, REPLY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long targetId;
    public Long brandId;
    public String email;
    @Enumerated(EnumType.STRING) public Kind kind;
    public Long messageId;
    public OffsetDateTime at;
}
