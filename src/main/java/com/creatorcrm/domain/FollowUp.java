package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.FollowUpStatus;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "followups")
public class FollowUp {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    public int number;
    public LocalDate scheduledDate;
    public LocalDate completedDate;
    @Enumerated(EnumType.STRING) public FollowUpStatus status;
}
