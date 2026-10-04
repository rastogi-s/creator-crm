package com.creatorcrm.repo;

import com.creatorcrm.domain.CampaignResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignResultRepo extends JpaRepository<CampaignResult, Long> {
    Optional<CampaignResult> findByOpportunityId(Long opportunityId);

    List<CampaignResult> findByMediaIdNotNullAndFetchedAtIsNull();
}
