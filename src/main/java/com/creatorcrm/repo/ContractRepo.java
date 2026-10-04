package com.creatorcrm.repo;

import com.creatorcrm.domain.Contract;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepo extends JpaRepository<Contract, Long> {
    List<Contract> findByOpportunityIdOrderByIdDesc(Long opportunityId);

    boolean existsByMessageId(Long messageId);
}
