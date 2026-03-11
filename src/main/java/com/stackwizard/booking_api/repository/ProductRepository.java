package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByTenantId(Long tenantId);
    Optional<Product> findFirstByTenantIdAndProductTypeIgnoreCaseOrderByIdAsc(Long tenantId, String productType);
}
