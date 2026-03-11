package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvoicePaymentAllocationRepository extends JpaRepository<InvoicePaymentAllocation, Long> {
    List<InvoicePaymentAllocation> findByInvoiceIdOrderByCreatedAtAsc(Long invoiceId);
    Optional<InvoicePaymentAllocation> findByInvoiceIdAndPaymentTransactionId(Long invoiceId, Long paymentTransactionId);

    @Query("select coalesce(sum(a.allocatedAmount), 0) from InvoicePaymentAllocation a where a.invoice.id = :invoiceId")
    BigDecimal sumAllocatedByInvoiceId(@Param("invoiceId") Long invoiceId);

    @Query("select coalesce(sum(a.allocatedAmount), 0) from InvoicePaymentAllocation a where a.paymentTransaction.id = :paymentTransactionId")
    BigDecimal sumAllocatedByPaymentTransactionId(@Param("paymentTransactionId") Long paymentTransactionId);

    @Query("""
            select a.paymentTransactionId as paymentTransactionId,
                   coalesce(sum(a.allocatedAmount), 0) as totalAllocated
            from InvoicePaymentAllocation a
            where a.paymentTransactionId in :paymentTransactionIds
            group by a.paymentTransactionId
            """)
    List<PaymentTransactionAllocationSum> sumAllocatedByPaymentTransactionIds(@Param("paymentTransactionIds") Collection<Long> paymentTransactionIds);

    interface PaymentTransactionAllocationSum {
        Long getPaymentTransactionId();
        BigDecimal getTotalAllocated();
    }
}
