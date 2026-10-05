package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmAccountContactRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmAccountContactRoleRepository extends JpaRepository<CrmAccountContactRole, Long> {
    List<CrmAccountContactRole> findByTenantIdAndAccountId(Long tenantId, Long accountId);
    Optional<CrmAccountContactRole> findByIdAndTenantId(Long id, Long tenantId);
}
