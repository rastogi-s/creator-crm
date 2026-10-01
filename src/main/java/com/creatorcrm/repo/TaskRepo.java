package com.creatorcrm.repo;

import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Task;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepo extends JpaRepository<Task, Long> {
    List<Task> findByStatus(TaskStatus status);

    List<Task> findByOpportunityIdAndStatus(Long opportunityId, TaskStatus status);

    List<Task> findByOpportunityIdOrderByCreatedAtDesc(Long opportunityId);

    List<Task> findByStatusAndCompletedAtAfter(TaskStatus status, OffsetDateTime after);
}
