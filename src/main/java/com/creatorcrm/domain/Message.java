package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.Direction;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "messages")
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long conversationId;
    public String externalId;
    @Enumerated(EnumType.STRING) public Direction direction;
    public String sender;
    public String senderName;
    public String recipient;
    public String subject;
    public String content;
    public String rfcMessageId;
    public String replyTo;
    public OffsetDateTime sentAt;
    public String messageType;
    public boolean aiProcessed;
    public String filteredReason;
}
