package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PlatformTenantMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformTenantMappingRepository extends JpaRepository<PlatformTenantMapping, Long> {
    Optional<PlatformTenantMapping> findByPlatformTenantId(UUID platformTenantId);

    Optional<PlatformTenantMapping> findByTenantId(Long tenantId);

    List<PlatformTenantMapping> findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(Long parentTenantId);

    List<PlatformTenantMapping> findByTenantIdIn(Collection<Long> tenantIds);

    @Query(value = "select nextval('booking_tenant_id_seq')", nativeQuery = true)
    Long nextTenantId();
}
