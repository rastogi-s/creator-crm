package com.creatorcrm.repo;

import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.FollowUp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FollowUpRepo extends JpaRepository<FollowUp, Long> {
    List<FollowUp> findByOpportunityIdOrderByNumberAsc(Long opportunityId);

    Optional<FollowUp> findFirstByOpportunityIdAndStatus(Long opportunityId, FollowUpStatus status);

    List<FollowUp> findByStatusAndScheduledDateLessThanEqualOrderByScheduledDateAsc(FollowUpStatus status, LocalDate date);

    List<FollowUp> findByStatusAndCompletedDate(FollowUpStatus status, LocalDate date);

    List<FollowUp> findByNumberAndStatusAndCompletedDateLessThanEqual(int number, FollowUpStatus status, LocalDate date);
}
