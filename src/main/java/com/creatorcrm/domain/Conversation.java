package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.Platform;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "conversations")
public class Conversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long brandId;
    @Enumerated(EnumType.STRING) public Platform platform;
    public String externalId;
    public String counterparty;
    public String subject;
    public String summary;
    public Boolean brandRelated;
    public OffsetDateTime createdAt;
    public OffsetDateTime lastMessageAt;
}
