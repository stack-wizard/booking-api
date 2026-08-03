package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long>, JpaSpecificationExecutor<PaymentTransaction> {
    Optional<PaymentTransaction> findByPaymentIntentId(Long paymentIntentId);
    Optional<PaymentTransaction> findFirstByPaymentIntentIdOrderByCreatedAtAscIdAsc(Long paymentIntentId);
    List<PaymentTransaction> findByReservationRequestIdOrderByCreatedAtAscIdAsc(Long reservationRequestId);
    List<PaymentTransaction> findBySourcePaymentTransactionId(Long sourcePaymentTransactionId);

    @Query("""
            select pt.sourcePaymentTransactionId as sourcePaymentTransactionId,
                   coalesce(sum(abs(pt.amount)), 0) as totalRefunded
            from PaymentTransaction pt
            where pt.sourcePaymentTransactionId in :sourcePaymentTransactionIds
              and upper(pt.transactionType) = 'REFUND'
              and upper(pt.status) = 'POSTED'
            group by pt.sourcePaymentTransactionId
            """)
    List<SourcePaymentRefundSum> sumRefundedBySourcePaymentTransactionIds(
            @Param("sourcePaymentTransactionIds") Collection<Long> sourcePaymentTransactionIds);

    interface SourcePaymentRefundSum {
        Long getSourcePaymentTransactionId();
        BigDecimal getTotalRefunded();
    }
}
