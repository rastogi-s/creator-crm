package com.creatorcrm.repo;

import com.creatorcrm.domain.Activity;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepo extends JpaRepository<Activity, Long> {
    List<Activity> findByAtAfterOrderByAtAsc(OffsetDateTime after);

    List<Activity> findByOpportunityIdOrderByAtDesc(Long opportunityId);
}
