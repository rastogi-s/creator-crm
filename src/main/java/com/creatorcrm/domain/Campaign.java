package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** One templated pitch per brand on a saved list, sent slowly from her Gmail after she approves each draft. */
@Entity
@Table(name = "campaigns")
public class Campaign {
    public enum Status { ACTIVE, PAUSED, DONE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    public Long listId;
    public Long templateId;
    /** One opening line per brand from Claude Haiku, written in a half-price batch. Off = no Claude at all. */
    public boolean personalise;
    @Enumerated(EnumType.STRING) public Status status = Status.ACTIVE;
    public OffsetDateTime createdAt;
}
