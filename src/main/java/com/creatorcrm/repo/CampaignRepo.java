package com.creatorcrm.repo;

import com.creatorcrm.domain.Campaign;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepo extends JpaRepository<Campaign, Long> {
    List<Campaign> findAllByOrderByIdDesc();

    List<Campaign> findByStatus(Campaign.Status status);

    boolean existsByListId(Long listId);

    boolean existsByTemplateId(Long templateId);
}
