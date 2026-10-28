package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmContactIdentifier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmContactIdentifierRepository extends JpaRepository<CrmContactIdentifier, Long> {
    Optional<CrmContactIdentifier> findByTenantIdAndTypeAndValueNorm(Long tenantId, CrmContactIdentifier.Type type, String valueNorm);

    List<CrmContactIdentifier> findByContactIdOrderByIdAsc(Long contactId);
}
