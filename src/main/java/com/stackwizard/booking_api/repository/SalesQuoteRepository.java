package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.SalesQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SalesQuoteRepository extends JpaRepository<SalesQuote, Long> {
    Optional<SalesQuote> findByIdAndTenantId(Long id, Long tenantId);

    List<SalesQuote> findByTenantIdAndEventIdOrderByIdDesc(Long tenantId, Long eventId);

    List<SalesQuote> findByTenantIdAndEventIdAndStatusIn(Long tenantId, Long eventId, Collection<SalesQuote.Status> statuses);

    List<SalesQuote> findByTenantIdAndStatusOrderByIdDesc(Long tenantId, SalesQuote.Status status);

    boolean existsByTenantIdAndEventIdAndStatus(Long tenantId, Long eventId, SalesQuote.Status status);

    Optional<SalesQuote> findFirstByTenantIdAndEventIdAndStatusOrderByIdDesc(Long tenantId, Long eventId, SalesQuote.Status status);

    @Query("select count(q) from SalesQuote q where q.tenantId = :tenantId and q.quoteNumber like :prefix")
    long countByNumberPrefix(@Param("tenantId") Long tenantId, @Param("prefix") String prefix);

    @Query("select q from SalesQuote q where q.status = com.stackwizard.booking_api.model.SalesQuote.Status.SENT and q.validUntil < :today")
    List<SalesQuote> findSentExpiredBefore(@Param("today") LocalDate today);
}
