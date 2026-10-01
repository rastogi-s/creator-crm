package com.creatorcrm.security;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "users")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String username;
    public String passwordHash;
    public OffsetDateTime createdAt;
}
