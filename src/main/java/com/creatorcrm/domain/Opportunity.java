package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "opportunities")
public class Opportunity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long brandId;
    public Long conversationId;
    @Enumerated(EnumType.STRING) public Origin origin;
    @Enumerated(EnumType.STRING) public OpportunityType type;
    @Enumerated(EnumType.STRING) public Compensation compensation;
    @Enumerated(EnumType.STRING) public OpportunityStatus status;
    public BigDecimal budgetAmount;
    public String currency;
    public String budgetText;
    public String deliverables;
    public String usageRights;
    public String campaign;
    public String missingInfo;
    public String nextStep;
    public String pitchPlatform;
    public LocalDate pitchedAt;
    public String initialResponse;
    public String closedReason;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
