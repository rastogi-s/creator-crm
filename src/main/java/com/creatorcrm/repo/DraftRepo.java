package com.creatorcrm.repo;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftRepo extends JpaRepository<Draft, Long> {
    List<Draft> findByStatusOrderByCreatedAtAsc(DraftStatus status);

    List<Draft> findByOpportunityIdAndStatus(Long opportunityId, DraftStatus status);

    boolean existsByFollowupIdAndStatus(Long followupId, DraftStatus status);

    boolean existsByTaskIdAndStatus(Long taskId, DraftStatus status);
}
