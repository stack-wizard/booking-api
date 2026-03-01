package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {
    Optional<PaymentEvent> findByProviderAndProviderEventId(String provider, String providerEventId);
    List<PaymentEvent> findByPaymentIntentIdOrderByCreatedAtDesc(Long paymentIntentId);
    List<PaymentEvent> findByPaymentIntentIdInOrderByCreatedAtDesc(List<Long> paymentIntentIds);
}
