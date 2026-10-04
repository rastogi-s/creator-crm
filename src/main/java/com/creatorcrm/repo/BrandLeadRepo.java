package com.creatorcrm.repo;

import com.creatorcrm.domain.BrandLead;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandLeadRepo extends JpaRepository<BrandLead, Long> {
    List<BrandLead> findByStatusOrderByIdDesc(BrandLead.Status status);

    boolean existsByNameKey(String nameKey);

    Optional<BrandLead> findFirstByNameKeyOrderByIdDesc(String nameKey);

    boolean existsByInstagramIgnoreCase(String instagram);

    Optional<BrandLead> findFirstByInstagramIgnoreCaseAndStatus(String instagram, BrandLead.Status status);
}
