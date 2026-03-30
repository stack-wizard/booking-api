package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long>, JpaSpecificationExecutor<PaymentTransaction> {
    Optional<PaymentTransaction> findByPaymentIntentId(Long paymentIntentId);
    List<PaymentTransaction> findBySourcePaymentTransactionId(Long sourcePaymentTransactionId);
}
