package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.Platform;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A message the creator sent, kept as an example of their voice (and of how they edit Claude's drafts). */
@Entity
@Table(name = "writing_examples")
public class WritingExample {
    public static final String APP_DRAFT = "APP_DRAFT";
    public static final String WRITTEN = "WRITTEN";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    public Long draftId;
    public String kind;
    @Enumerated(EnumType.STRING) public Platform channel;
    public String source;
    public String brandName;
    public String aiSubject;
    public String aiBody;
    public String sentSubject;
    public String sentBody;
    public boolean edited;
    public boolean gotReply;
    public boolean excluded;
    public OffsetDateTime sentAt;
    public OffsetDateTime repliedAt;
}
