package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ProductPackageListing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductPackageListingRepository extends JpaRepository<ProductPackageListing, Long> {
    Optional<ProductPackageListing> findByTenantIdAndProductId(Long tenantId, Long productId);

    List<ProductPackageListing> findByTenantIdAndPublishedTrueOrderByIdAsc(Long tenantId);
}
