package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** One run of content ideas for a deal: the brand's guidelines it was based on, and the ideas Claude wrote. */
@Entity
@Table(name = "content_ideas")
public class ContentIdeas {
    /** EMAIL: the brand's brief email and its files. PASTED: guidelines she pasted. DEAL: no brief, just the deal. */
    public enum Source { EMAIL, PASTED, DEAL }

    public enum Status { READY, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    public Long messageId;
    @Enumerated(EnumType.STRING) public Source source;
    @Enumerated(EnumType.STRING) public Status status;
    public String guidelines;
    /** The brief's files that were read, comma-separated. */
    public String fileNames;
    /** What she asked for when she refreshed ("funnier", "no talking to camera"). */
    public String note;
    public String ideasJson;
    public String error;
    public OffsetDateTime createdAt;
}
