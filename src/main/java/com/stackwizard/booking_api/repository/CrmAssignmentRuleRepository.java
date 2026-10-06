package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmAssignmentRule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CrmAssignmentRuleRepository extends JpaRepository<CrmAssignmentRule, Long> {
    List<CrmAssignmentRule> findByTenantIdOrderByPriorityAscIdAsc(Long tenantId);
    List<CrmAssignmentRule> findByTenantIdAndActiveTrueOrderByPriorityAscIdAsc(Long tenantId);
    Optional<CrmAssignmentRule> findByIdAndTenantId(Long id, Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CrmAssignmentRule r where r.id = :id")
    Optional<CrmAssignmentRule> lockById(@Param("id") Long id);
}
