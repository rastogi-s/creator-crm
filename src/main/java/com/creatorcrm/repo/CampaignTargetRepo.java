package com.creatorcrm.repo;

import com.creatorcrm.domain.CampaignTarget;
import com.creatorcrm.domain.CampaignTarget.State;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignTargetRepo extends JpaRepository<CampaignTarget, Long> {
    List<CampaignTarget> findByCampaignIdOrderByIdAsc(Long campaignId);

    List<CampaignTarget> findByBrandId(Long brandId);

    List<CampaignTarget> findByStateOrderByApprovedAtAscIdAsc(State state);

    List<CampaignTarget> findByStateIn(Collection<State> states);

    Optional<CampaignTarget> findByDraftId(Long draftId);

    Optional<CampaignTarget> findFirstByOpportunityIdOrderByIdDesc(Long opportunityId);

    long countBySentAtGreaterThanEqual(OffsetDateTime since);

    boolean existsByBrandIdAndSentAtGreaterThanEqual(Long brandId, OffsetDateTime since);

    Optional<CampaignTarget> findFirstBySentAtNotNullOrderBySentAtAsc();
}
