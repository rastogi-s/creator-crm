package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.DealStage;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A deal reaching a stage, and when: the email's date when an email moved it, otherwise the moment it moved. */
@Entity
@Table(name = "deal_stages")
public class StageEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    @Enumerated(EnumType.STRING) public DealStage stage;
    public OffsetDateTime reachedAt;

    public static StageEntry of(Long opportunityId, DealStage stage, OffsetDateTime at) {
        StageEntry e = new StageEntry();
        e.opportunityId = opportunityId;
        e.stage = stage;
        e.reachedAt = at;
        return e;
    }
}
