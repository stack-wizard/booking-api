package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmStageTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrmStageTransitionRepository extends JpaRepository<CrmStageTransition, Long> {
    List<CrmStageTransition> findByTenantIdAndOpportunityIdOrderByChangedAtAsc(Long tenantId, Long opportunityId);
}
