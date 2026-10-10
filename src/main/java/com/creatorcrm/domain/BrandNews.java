package com.creatorcrm.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Something a brand she has worked with launched, teased or restocked, with where it was found. Claude's sort adds
 * the kind, a one-line headline and how well it fits her; the best fresh item opens that brand's next re-pitch.
 */
@Entity
@Table(name = "brand_news")
public class BrandNews {
    public enum Source { EMAIL, INSTAGRAM, WEBSITE, NEWS, WEB_SEARCH }

    public enum Kind { LAUNCH, TEASER, RESTOCK, NEW_RANGE, CAMPAIGN, OTHER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long brandId;
    @Enumerated(EnumType.STRING) public Source source;
    public String itemKey;
    public String title;
    public String snippet;
    public String url;
    /** Source EMAIL: the brand's newsletter in her inbox. */
    public Long messageId;
    /** When the brand published it, when the source says; else null and {@link #foundAt} stands in. */
    public OffsetDateTime publishedAt;
    public OffsetDateTime foundAt;
    /** Null until Claude has sorted it. */
    @Enumerated(EnumType.STRING) public Kind kind;
    public String headline;
    public String pitchIdea;
    /** 0-100: how well it fits her niche and audience. */
    public Integer fit;
    /** Not news, off her niche, or she hid it: never used in a re-pitch. */
    public boolean hidden;
    /** The re-pitch that opened with it; each item is used once. */
    public Long usedInDraftId;

    public OffsetDateTime date() {
        return publishedAt != null ? publishedAt : foundAt;
    }
}
