package com.creatorcrm.repo;

import com.creatorcrm.domain.Suppression;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuppressionRepo extends JpaRepository<Suppression, Long> {
    Optional<Suppression> findByValue(String value);
}
