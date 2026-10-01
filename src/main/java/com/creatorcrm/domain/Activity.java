package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** Append-only log of what happened; feeds the end-of-day summary. */
@Entity
@Table(name = "activity")
public class Activity {
    public static final String TASK_DONE = "TASK_DONE";
    public static final String BRAND_REPLIED = "BRAND_REPLIED";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
    public static final String NEW_OPPORTUNITY = "NEW_OPPORTUNITY";
    public static final String FOLLOWUP_SENT = "FOLLOWUP_SENT";
    public static final String DRAFT_SENT = "DRAFT_SENT";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    public String type;
    public String text;
    public OffsetDateTime at;

    public static Activity of(Long opportunityId, String type, String text) {
        Activity a = new Activity();
        a.opportunityId = opportunityId;
        a.type = type;
        a.text = text;
        a.at = OffsetDateTime.now();
        return a;
    }
}
