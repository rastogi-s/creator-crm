package com.creatorcrm.repo;

import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Enums.DeadlineType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadlineRepo extends JpaRepository<Deadline, Long> {
    boolean existsByOpportunityIdAndTypeAndDueDate(Long opportunityId, DeadlineType type, LocalDate dueDate);

    List<Deadline> findByDoneFalseAndDueDateLessThanEqualOrderByDueDateAsc(LocalDate date);

    List<Deadline> findByOpportunityIdOrderByDueDateAsc(Long opportunityId);
}
