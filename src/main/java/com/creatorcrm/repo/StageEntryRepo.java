package com.creatorcrm.repo;

import com.creatorcrm.domain.StageEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StageEntryRepo extends JpaRepository<StageEntry, Long> {
    List<StageEntry> findByOpportunityIdOrderByReachedAtAscIdAsc(Long opportunityId);
}
