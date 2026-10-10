package com.creatorcrm.repo;

import com.creatorcrm.domain.BrandNews;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandNewsRepo extends JpaRepository<BrandNews, Long> {
    boolean existsByBrandIdAndItemKey(Long brandId, String itemKey);

    List<BrandNews> findByBrandId(Long brandId);

    Optional<BrandNews> findFirstByUsedInDraftId(Long draftId);
}
