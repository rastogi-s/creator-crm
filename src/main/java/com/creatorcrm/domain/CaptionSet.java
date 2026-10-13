package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** Captions for one post on YouTube, Instagram and TikTok, and what they were written from. */
@Entity
@Table(name = "caption_sets")
public class CaptionSet {
    public enum Status { READY, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String title;
    /** What the video is about, in her words. */
    public String about;
    /** What she asked for ("funnier", "mention the code"). */
    public String note;
    public Long opportunityId;
    /** The products in the post, comma-separated ids, in the order shown. */
    public String productIds;
    /** SHORT or VIDEO. */
    public String youtubeFormat;
    /** Comma-separated: YOUTUBE, INSTAGRAM, TIKTOK. */
    public String platforms;
    public int pictureCount;
    @Enumerated(EnumType.STRING) public Status status;
    /** Claude's captions, without links: the app adds those from the products when it shows them. */
    public String draftJson;
    public String error;
    public OffsetDateTime createdAt;
}
