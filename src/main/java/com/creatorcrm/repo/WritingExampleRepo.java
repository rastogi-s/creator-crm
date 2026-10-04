package com.creatorcrm.repo;

import com.creatorcrm.domain.WritingExample;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WritingExampleRepo extends JpaRepository<WritingExample, Long> {
    List<WritingExample> findTop300ByExcludedFalseOrderBySentAtDesc();

    List<WritingExample> findTop50ByOrderBySentAtDesc();

    List<WritingExample> findByOpportunityIdOrderBySentAtDesc(Long opportunityId);

    long countByExcludedFalse();

    long countByExcludedFalseAndEditedTrue();

    long countByExcludedFalseAndGotReplyTrue();
}
