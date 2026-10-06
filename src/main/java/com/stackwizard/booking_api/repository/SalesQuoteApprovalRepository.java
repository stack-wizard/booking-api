package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.SalesQuoteApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalesQuoteApprovalRepository extends JpaRepository<SalesQuoteApproval, Long> {
    List<SalesQuoteApproval> findByTenantIdAndQuoteIdOrderByCreatedAtDescIdDesc(Long tenantId, Long quoteId);

    Optional<SalesQuoteApproval> findFirstByTenantIdAndQuoteIdAndStatus(Long tenantId, Long quoteId, SalesQuoteApproval.Status status);

    List<SalesQuoteApproval> findByTenantIdAndStatusOrderByCreatedAtAsc(Long tenantId, SalesQuoteApproval.Status status);
}
