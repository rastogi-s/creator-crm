package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.*;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "drafts")
public class Draft {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    public Long conversationId;
    public Long taskId;
    public Long followupId;
    @Enumerated(EnumType.STRING) public DraftType type;
    @Enumerated(EnumType.STRING) public Platform channel;
    public String toAddress;
    public String subject;
    public String body;
    public String inReplyTo;
    public String gmailThreadId;
    public String gmailDraftId;
    @Enumerated(EnumType.STRING) public DraftStatus status;
    public String error;
    public OffsetDateTime createdAt;
    public OffsetDateTime sentAt;
}
