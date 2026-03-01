package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface InvoicePaymentAllocationRepository extends JpaRepository<InvoicePaymentAllocation, Long> {
    List<InvoicePaymentAllocation> findByInvoiceIdOrderByCreatedAtAsc(Long invoiceId);
    Optional<InvoicePaymentAllocation> findByInvoiceIdAndPaymentIntentId(Long invoiceId, Long paymentIntentId);

    @Query("select coalesce(sum(a.allocatedAmount), 0) from InvoicePaymentAllocation a where a.invoice.id = :invoiceId")
    BigDecimal sumAllocatedByInvoiceId(@Param("invoiceId") Long invoiceId);

    @Query("select coalesce(sum(a.allocatedAmount), 0) from InvoicePaymentAllocation a where a.paymentIntent.id = :paymentIntentId")
    BigDecimal sumAllocatedByPaymentIntentId(@Param("paymentIntentId") Long paymentIntentId);
}
