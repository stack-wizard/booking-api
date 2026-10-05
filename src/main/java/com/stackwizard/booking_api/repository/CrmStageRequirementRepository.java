package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmStageRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmStageRequirementRepository extends JpaRepository<CrmStageRequirement, Long> {
    List<CrmStageRequirement> findByTenantIdAndStageId(Long tenantId, Long stageId);
    Optional<CrmStageRequirement> findByIdAndTenantId(Long id, Long tenantId);
}
