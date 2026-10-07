package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A saved filter over the contacts database (see {@code ContactFilter}), e.g. "Partnerships inboxes, never emailed". */
@Entity
@Table(name = "contact_lists")
public class ContactList {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    /** The filter as JSON, so new filter fields need no migration. */
    public String filterJson;
    public OffsetDateTime createdAt;
}
