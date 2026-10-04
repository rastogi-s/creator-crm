package com.creatorcrm.domain;

import com.creatorcrm.domain.Enums.InvoiceStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** An invoice for a deal. Line items are stored as JSON; see {@code InvoiceService.LineItem}. */
@Entity
@Table(name = "invoices")
public class Invoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String number;
    public int invoiceYear;
    public int seq;
    public Long opportunityId;
    public Long brandId;
    public String billTo;
    public String billToEmail;
    public String currency;
    public BigDecimal amount;
    public String lineItems;
    public String notes;
    public LocalDate issuedDate;
    public LocalDate dueDate;
    public OffsetDateTime sentAt;
    public LocalDate paidDate;
    @Enumerated(EnumType.STRING) public InvoiceStatus status;
    public OffsetDateTime createdAt;

    /** Sent, not paid, and past its due date. */
    public boolean isOverdue(LocalDate today) {
        return status == InvoiceStatus.SENT && dueDate != null && dueDate.isBefore(today);
    }
}
