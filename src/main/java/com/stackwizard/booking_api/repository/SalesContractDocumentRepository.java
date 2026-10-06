package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.SalesContractDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalesContractDocumentRepository extends JpaRepository<SalesContractDocument, Long> {
    List<SalesContractDocument> findByTenantIdAndContractIdOrderByCreatedAtDescIdDesc(Long tenantId, Long contractId);

    Optional<SalesContractDocument> findByIdAndTenantId(Long id, Long tenantId);
}
