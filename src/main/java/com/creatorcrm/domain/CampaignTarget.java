package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/** One brand in a campaign: who gets the pitch, and where it is. Attempt 2 is the one retry with the next person. */
@Entity
@Table(name = "campaign_targets")
public class CampaignTarget {
    public enum State {
        /** Waiting for its personalised opening line from the overnight batch. */
        WAITING_LINE,
        /** A pitch is in Drafts for her to read and approve. */
        DRAFTED,
        /** Approved: in the slow send queue. */
        APPROVED,
        /** Sent; follow-ups run on the deal like any pitch. */
        SENT,
        REPLIED, BOUNCED, OPTED_OUT,
        /** No reply after every follow-up. */
        COLD,
        /** Went cold, and the next-ranked person at the brand was tried. */
        MOVED_ON,
        /** She discarded the draft, or the brand couldn't be pitched. */
        SKIPPED,
        /** Someone at the brand replied (or the campaign ended) before this went out. */
        STOPPED;

        public static final Set<State> WAITING = EnumSet.of(WAITING_LINE, DRAFTED, APPROVED);
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long campaignId;
    public Long brandId;
    public Long contactId;
    public String email;
    public int attempt = 1;
    @Enumerated(EnumType.STRING) public State state;
    public Long opportunityId;
    public Long draftId;
    public String openingLine;
    public String batchId;
    public OffsetDateTime approvedAt;
    public OffsetDateTime sentAt;
    public String endedReason;
    public OffsetDateTime createdAt;
}
