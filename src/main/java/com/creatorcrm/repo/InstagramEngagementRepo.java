package com.creatorcrm.repo;

import com.creatorcrm.domain.InstagramEngagement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstagramEngagementRepo extends JpaRepository<InstagramEngagement, Long> {
    Optional<InstagramEngagement> findByUsername(String username);

    List<InstagramEngagement> findByStatusOrderByLastSeenAtDesc(InstagramEngagement.Status status);

    List<InstagramEngagement> findTop10ByStatusAndCheckedAtIsNullOrderByLastSeenAtDesc(InstagramEngagement.Status status);
}
