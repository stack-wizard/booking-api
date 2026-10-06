package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.SalesQuoteLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalesQuoteLineRepository extends JpaRepository<SalesQuoteLine, Long> {
    List<SalesQuoteLine> findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(Long tenantId, Long quoteId);

    Optional<SalesQuoteLine> findByIdAndTenantIdAndQuoteId(Long id, Long tenantId, Long quoteId);
}
