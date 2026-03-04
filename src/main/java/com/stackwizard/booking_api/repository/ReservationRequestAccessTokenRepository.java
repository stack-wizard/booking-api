package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ReservationRequestAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReservationRequestAccessTokenRepository extends JpaRepository<ReservationRequestAccessToken, Long> {
    Optional<ReservationRequestAccessToken> findByToken(String token);
    List<ReservationRequestAccessToken> findByReservationRequestIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long reservationRequestId);
}
