package com.creatorcrm.repo;

import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Invoice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InvoiceRepo extends JpaRepository<Invoice, Long> {
    List<Invoice> findByOpportunityIdOrderByIdAsc(Long opportunityId);

    List<Invoice> findByStatusOrderByDueDateAsc(InvoiceStatus status);

    List<Invoice> findAllByOrderByIssuedDateDescIdDesc();

    Optional<Invoice> findFirstByOpportunityIdAndStatusOrderByIdDesc(Long opportunityId, InvoiceStatus status);

    @Query("select coalesce(max(i.seq), 0) from Invoice i where i.invoiceYear = ?1")
    int maxSeq(int year);
}
