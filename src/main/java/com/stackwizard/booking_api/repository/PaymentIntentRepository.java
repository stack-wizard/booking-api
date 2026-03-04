package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {
    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentIntent> findByProviderAndProviderOrderNumber(String provider, String providerOrderNumber);
    Optional<PaymentIntent> findByProviderAndProviderPaymentId(String provider, String providerPaymentId);
    List<PaymentIntent> findByReservationRequestId(Long reservationRequestId);
    List<PaymentIntent> findByReservationRequestIdOrderByCreatedAtDesc(Long reservationRequestId);
    List<PaymentIntent> findByReservationRequestIdAndStatusInOrderByCreatedAtDesc(Long reservationRequestId, List<String> statuses);
    List<PaymentIntent> findByStatusInAndExpiresAtBefore(List<String> statuses, OffsetDateTime threshold);
}
