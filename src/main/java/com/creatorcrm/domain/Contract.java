package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A contract a brand sent, with the terms Claude read from it and the flags the app's rules raised. */
@Entity
@Table(name = "contracts")
public class Contract {
    public enum Source { PDF, PASTED, LINK }

    /** CHECKED: terms read and checked. LINK_ONLY: an e-signature link the app can't open. UNREADABLE: a scanned PDF. */
    public enum Status { CHECKED, LINK_ONLY, UNREADABLE, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public Long opportunityId;
    public Long messageId;
    public String fileName;
    @Enumerated(EnumType.STRING) public Source source;
    @Enumerated(EnumType.STRING) public Status status;
    public String textContent;
    public String termsJson;
    public String flagsJson;
    public String error;
    public OffsetDateTime createdAt;
    public OffsetDateTime checkedAt;
}
