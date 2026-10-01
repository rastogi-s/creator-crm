package com.creatorcrm.repo;

import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Opportunity;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OpportunityRepo extends JpaRepository<Opportunity, Long> {
    Optional<Opportunity> findFirstByConversationIdOrderByIdDesc(Long conversationId);

    List<Opportunity> findByBrandIdOrderByIdDesc(Long brandId);

    List<Opportunity> findByStatusNotInOrderByUpdatedAtDesc(Collection<OpportunityStatus> statuses);

    List<Opportunity> findByOriginOrderByPitchedAtDesc(Origin origin);

    List<Opportunity> findByCreatedAtAfter(OffsetDateTime after);
}
