package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.DepositPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepositPolicyRepository extends JpaRepository<DepositPolicy, Long> {
    List<DepositPolicy> findByTenantId(Long tenantId);
}
