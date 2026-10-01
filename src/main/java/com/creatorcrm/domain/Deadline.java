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
}
