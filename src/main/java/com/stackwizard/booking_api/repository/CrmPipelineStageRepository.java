package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmPipelineStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmPipelineStageRepository extends JpaRepository<CrmPipelineStage, Long> {
    List<CrmPipelineStage> findByTenantIdAndPipelineIdOrderByDisplayOrderAsc(Long tenantId, Long pipelineId);
    Optional<CrmPipelineStage> findByIdAndTenantId(Long id, Long tenantId);
    Optional<CrmPipelineStage> findFirstByTenantIdAndPipelineIdOrderByDisplayOrderAsc(Long tenantId, Long pipelineId);
}
