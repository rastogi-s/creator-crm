package com.creatorcrm.repo;

import com.creatorcrm.domain.BrandContact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandContactRepo extends JpaRepository<BrandContact, Long> {
    Optional<BrandContact> findByEmail(String email);

    List<BrandContact> findByBrandIdOrderByScoreDescIdAsc(Long brandId);

    List<BrandContact> findAllByOrderByScoreDescIdAsc();
}
