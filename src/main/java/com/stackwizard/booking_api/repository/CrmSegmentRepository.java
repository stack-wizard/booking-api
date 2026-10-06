package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmSegmentRepository extends JpaRepository<CrmSegment, Long> {
    List<CrmSegment> findByTenantIdOrderByDisplayOrderAscNameAsc(Long tenantId);
    Optional<CrmSegment> findByIdAndTenantId(Long id, Long tenantId);
    Optional<CrmSegment> findByTenantIdAndCodeIgnoreCase(Long tenantId, String code);
    boolean existsByTenantIdAndActiveTrue(Long tenantId);
}
