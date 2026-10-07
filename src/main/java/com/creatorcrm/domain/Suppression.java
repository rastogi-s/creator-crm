package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** An address or whole domain the app must never email. Checked before every send; never pruned. */
@Entity
@Table(name = "suppression_list")
public class Suppression {
    public enum Kind { EMAIL, DOMAIN }
    public enum Reason { OPTED_OUT, BOUNCED, FORGET_ME, MANUAL }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    /** A lower-case email address, or a bare domain. */
    @Column(name = "address") public String value;
    @Enumerated(EnumType.STRING) public Kind kind;
    @Enumerated(EnumType.STRING) public Reason reason;
    public OffsetDateTime addedAt;
}
