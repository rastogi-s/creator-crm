package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.*;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "tasks")
public class Task {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    @Enumerated(EnumType.STRING) public TaskType type;
    public String description;
    @Enumerated(EnumType.STRING) public Priority priority;
    public LocalDate dueDate;
    @Enumerated(EnumType.STRING) public TaskStatus status;
    public Integer followupNumber;
    public Long sourceMessageId;
    public OffsetDateTime createdAt;
    public OffsetDateTime completedAt;
}
