package com.creatorcrm.security;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "secrets")
public class Secret {
    @Id public String name;
    public String valueEnc;
    public OffsetDateTime updatedAt;
}
