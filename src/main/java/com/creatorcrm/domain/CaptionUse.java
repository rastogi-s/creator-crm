package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** One caption field she copied from Post captions, with what was shown to her, so new captions learn from her edits. */
@Entity
@Table(name = "caption_uses")
public class CaptionUse {
    public enum Field { YOUTUBE_TITLE, YOUTUBE_DESCRIPTION, YOUTUBE_PINNED, INSTAGRAM_CAPTION, INSTAGRAM_STORY, TIKTOK_CAPTION, DM_REPLY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long captionSetId;
    @Enumerated(EnumType.STRING) public Field field;
    /** What the app showed her: Claude's text with her links added. */
    public String writtenText;
    /** What she copied, edits included. */
    public String usedText;
    public boolean edited;
    public OffsetDateTime usedAt;
}
