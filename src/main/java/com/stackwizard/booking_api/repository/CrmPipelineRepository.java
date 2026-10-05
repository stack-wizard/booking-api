package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmPipeline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmPipelineRepository extends JpaRepository<CrmPipeline, Long> {
    List<CrmPipeline> findByTenantIdOrderByNameAsc(Long tenantId);
    Optional<CrmPipeline> findByIdAndTenantId(Long id, Long tenantId);
    Optional<CrmPipeline> findByTenantIdAndIsDefaultTrue(Long tenantId);
}
