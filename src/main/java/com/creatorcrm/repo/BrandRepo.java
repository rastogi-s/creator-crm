package com.creatorcrm.repo;

import com.creatorcrm.domain.Brand;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepo extends JpaRepository<Brand, Long> {
    Optional<Brand> findByNameKey(String nameKey);

    Optional<Brand> findFirstByContactEmailIgnoreCase(String email);
}
