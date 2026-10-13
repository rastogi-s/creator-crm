package com.creatorcrm.repo;

import com.creatorcrm.domain.Product;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepo extends JpaRepository<Product, Long> {
    List<Product> findAllByOrderByIdDesc();
}
