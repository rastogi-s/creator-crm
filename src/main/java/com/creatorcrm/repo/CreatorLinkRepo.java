package com.creatorcrm.repo;

import com.creatorcrm.domain.CreatorLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorLinkRepo extends JpaRepository<CreatorLink, Long> {
    List<CreatorLink> findAllByOrderBySortOrderAscIdAsc();
}
