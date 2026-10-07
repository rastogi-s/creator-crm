package com.creatorcrm.repo;

import com.creatorcrm.domain.PitchTemplate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PitchTemplateRepo extends JpaRepository<PitchTemplate, Long> {
    List<PitchTemplate> findAllByOrderByIdAsc();
}
