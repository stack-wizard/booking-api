package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ProductComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductComponentRepository extends JpaRepository<ProductComponent, Long> {
    List<ProductComponent> findByTenantIdAndPackageProductIdOrderByDisplayOrderAscIdAsc(Long tenantId, Long packageProductId);

    boolean existsByTenantIdAndPackageProductId(Long tenantId, Long packageProductId);

    @Modifying
    @Query("delete from ProductComponent c where c.tenantId = :tenantId and c.packageProductId = :packageProductId")
    void deleteByTenantIdAndPackageProductId(@Param("tenantId") Long tenantId,
                                             @Param("packageProductId") Long packageProductId);
}
