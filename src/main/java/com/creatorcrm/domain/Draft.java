package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "drafts")
public class Draft {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    public Long conversationId;
    public Long taskId;
    public Long followupId;
    /** Set for an invoice email: the invoice's PDF goes out attached, and sending marks the invoice sent. */
    public Long invoiceId;
    /** Set for a results recap: the campaign results PDF goes out attached. */
    public Long resultId;
    @Enumerated(EnumType.STRING) public DraftType type;
    @Enumerated(EnumType.STRING) public Platform channel;
    public String toAddress;
    public String subject;
    public String body;
    /** What Claude wrote, before any edits; compared with the sent text to learn from the creator's changes. */
    public String originalSubject;
    public String originalBody;
    public String inReplyTo;
    public String gmailThreadId;
    public String gmailDraftId;
    @Enumerated(EnumType.STRING) public DraftStatus status;
    public String error;
    public OffsetDateTime createdAt;
    public OffsetDateTime sentAt;

    /** Files to attach when this draft is mirrored to Gmail or sent. Filled in just before, never stored. */
    @Transient @JsonIgnore public List<Attachment> attachments = List.of();
}
