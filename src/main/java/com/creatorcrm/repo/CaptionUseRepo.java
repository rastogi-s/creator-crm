package com.creatorcrm.repo;

import com.creatorcrm.domain.CaptionUse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaptionUseRepo extends JpaRepository<CaptionUse, Long> {
    List<CaptionUse> findTop200ByOrderByUsedAtDesc();

    Optional<CaptionUse> findByCaptionSetIdAndField(Long captionSetId, CaptionUse.Field field);

    long countByEdited(boolean edited);
}
