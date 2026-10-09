package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ProductProperty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProductPropertyRepository extends JpaRepository<ProductProperty, Long> {
    List<ProductProperty> findByTenantId(Long tenantId);

    List<ProductProperty> findByProductIdAndTenantIdIn(Long productId, Collection<Long> tenantIds);

    void deleteByProductIdAndTenantIdIn(Long productId, Collection<Long> tenantIds);
}
