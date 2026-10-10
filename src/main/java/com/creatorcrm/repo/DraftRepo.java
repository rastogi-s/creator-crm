package com.creatorcrm.repo;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DraftRepo extends JpaRepository<Draft, Long> {
    List<Draft> findByStatusOrderByCreatedAtAsc(DraftStatus status);

    List<Draft> findByOpportunityIdAndStatus(Long opportunityId, DraftStatus status);

    boolean existsByFollowupIdAndStatus(Long followupId, DraftStatus status);

    boolean existsByTaskIdAndStatus(Long taskId, DraftStatus status);

    /** The campaign send queue: approved emails not sent yet, oldest approval first. */
    List<Draft> findByStatusAndApprovedAtNotNullOrderByApprovedAtAscIdAsc(DraftStatus status);

    /** Scheduled emails whose time has come, earliest first. */
    List<Draft> findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(DraftStatus status, OffsetDateTime now);

    List<Draft> findByCampaignTargetIdAndStatus(Long campaignTargetId, DraftStatus status);
}
