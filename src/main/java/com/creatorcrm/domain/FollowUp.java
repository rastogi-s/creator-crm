package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.FollowUpStatus;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "followups")
public class FollowUp {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    public int number;
    public LocalDate scheduledDate;
    public LocalDate completedDate;
    @Enumerated(EnumType.STRING) public FollowUpStatus status;
    /** Why the app stopped this follow-up on its own (e.g. the email bounced); null when the brand replied or she stopped it. */
    public String stoppedReason;
    public OffsetDateTime stoppedAt;
}
