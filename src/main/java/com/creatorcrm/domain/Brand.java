package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "brands")
public class Brand {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    public String nameKey;
    public String website;
    public String contactName;
    public String contactEmail;
    public String instagram;
    public String notes;
    public OffsetDateTime createdAt;

    /** Normalized brand name used to detect duplicates ("Glow Co." == "glowco"). */
    public static String key(String name) {
        String k = name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String suffix : new String[] {"official", "inc", "llc", "ltd", "co", "hq"}) {
            if (k.endsWith(suffix) && k.length() > suffix.length() + 2) {
                k = k.substring(0, k.length() - suffix.length());
            }
        }
        return k;
    }
}
