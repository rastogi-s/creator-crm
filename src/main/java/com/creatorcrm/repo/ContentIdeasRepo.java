package com.creatorcrm.repo;

import com.creatorcrm.domain.ContentIdeas;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentIdeasRepo extends JpaRepository<ContentIdeas, Long> {
    Optional<ContentIdeas> findFirstByOpportunityIdOrderByIdDesc(Long opportunityId);

    List<ContentIdeas> findByOpportunityIdOrderByIdDesc(Long opportunityId);

    boolean existsByMessageId(Long messageId);

    boolean existsByOpportunityIdAndStatus(Long opportunityId, ContentIdeas.Status status);
}
