package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {
    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentIntent> findByProviderAndProviderOrderNumber(String provider, String providerOrderNumber);
    Optional<PaymentIntent> findByProviderAndProviderPaymentId(String provider, String providerPaymentId);
    List<PaymentIntent> findByReservationRequestId(Long reservationRequestId);
    List<PaymentIntent> findByReservationRequestIdOrderByCreatedAtDesc(Long reservationRequestId);
}
