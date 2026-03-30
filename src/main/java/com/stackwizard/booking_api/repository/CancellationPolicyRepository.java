package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CancellationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CancellationPolicyRepository extends JpaRepository<CancellationPolicy, Long> {
    List<CancellationPolicy> findByTenantIdOrderByPriorityDescIdDesc(Long tenantId);
    List<CancellationPolicy> findByTenantIdAndActiveTrueOrderByPriorityDescIdDesc(Long tenantId);
}
