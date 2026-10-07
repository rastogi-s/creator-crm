package com.creatorcrm.repo;

import com.creatorcrm.domain.CampaignEvent;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignEventRepo extends JpaRepository<CampaignEvent, Long> {
    long countByKindAndAtGreaterThanEqual(CampaignEvent.Kind kind, OffsetDateTime since);

    boolean existsByMessageId(Long messageId);
}
