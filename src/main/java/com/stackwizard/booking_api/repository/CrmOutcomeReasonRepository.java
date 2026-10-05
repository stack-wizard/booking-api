package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmOutcomeReason;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmOutcomeReasonRepository extends JpaRepository<CrmOutcomeReason, Long> {
    List<CrmOutcomeReason> findByTenantIdOrderByDisplayOrderAsc(Long tenantId);
    Optional<CrmOutcomeReason> findByIdAndTenantId(Long id, Long tenantId);
}
