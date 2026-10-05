package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmContactRepository extends JpaRepository<CrmContact, Long> {
    Optional<CrmContact> findByIdAndTenantId(Long id, Long tenantId);
    List<CrmContact> findByTenantIdAndAccountIdOrderByLastNameAsc(Long tenantId, Long accountId);
    List<CrmContact> findByTenantIdOrderByLastNameAsc(Long tenantId);
}
