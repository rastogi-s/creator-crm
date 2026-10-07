package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * One place a contact was seen. Contacts from paid finder services (HUNTER, APOLLO, FINDER = any other) and lists she
 * bought or got from someone else (THIRD_PARTY) must never be shared or sold.
 */
@Entity
@Table(name = "contact_sources")
public class ContactSource {
    public enum Kind {
        GMAIL, WEBSITE, HUNTER, APOLLO, FINDER, IMPORT, THIRD_PARTY, MANUAL, LEAD, GUESS;

        /** For her own outreach only: finder services' terms forbid resale, and someone else's list isn't hers to share. */
        public boolean licensed() {
            return this == HUNTER || this == APOLLO || this == FINDER || this == THIRD_PARTY;
        }
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long contactId;
    @Enumerated(EnumType.STRING) public Kind source;
    public String sourceUrl;
    public OffsetDateTime foundAt;
}
