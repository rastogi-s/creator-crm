package com.creatorcrm.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** A product she recommends, with her affiliate link: Amazon, Walmart or any other shop. */
@Entity
@Table(name = "products")
public class Product {
    public enum Store { AMAZON, WALMART, OTHER }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    @Enumerated(EnumType.STRING) public Store store;
    public String url;
    /** Amazon ASIN or Walmart item number, when the link has one. */
    public String itemId;
    public String brand;
    /** Comma-separated, lower case ("skincare, morning routine"). */
    public String tags;
    /** A discount code that goes with the link, e.g. AVA20. */
    public String code;
    public int timesUsed;
    public OffsetDateTime lastUsedAt;
    public OffsetDateTime createdAt;
}
