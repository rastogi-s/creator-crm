package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.DeadlineType;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "deadlines")
public class Deadline {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    @Enumerated(EnumType.STRING) public DeadlineType type;
    public LocalDate dueDate;
    public String description;
    public boolean done;
    /** Why the app cleared it on its own (the deal moved past it, the date passed); null if she did. */
    public String doneReason;
    public java.time.OffsetDateTime doneAt;
    /** She reopened it after the app cleared it: the date rules leave it alone from then on. */
    public boolean keptOpen;
    /** The event on her "Creator CRM" Google calendar, and a fingerprint of what it last showed. */
    public String calendarEventId;
    public String calendarFingerprint;
}
