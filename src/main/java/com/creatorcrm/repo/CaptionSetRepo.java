package com.creatorcrm.repo;

import com.creatorcrm.domain.CaptionSet;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaptionSetRepo extends JpaRepository<CaptionSet, Long> {
    List<CaptionSet> findTop30ByOrderByIdDesc();
}
