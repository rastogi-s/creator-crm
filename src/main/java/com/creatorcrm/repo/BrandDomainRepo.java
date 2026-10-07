package com.creatorcrm.repo;

import com.creatorcrm.domain.BrandDomain;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandDomainRepo extends JpaRepository<BrandDomain, Long> {
    Optional<BrandDomain> findByDomain(String domain);

    List<BrandDomain> findByBrandId(Long brandId);
}
