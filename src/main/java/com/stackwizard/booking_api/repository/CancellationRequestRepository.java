package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CancellationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CancellationRequestRepository extends JpaRepository<CancellationRequest, Long> {
    List<CancellationRequest> findByReservationRequestIdOrderByCreatedAtDescIdDesc(Long reservationRequestId);
    Optional<CancellationRequest> findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(Long reservationRequestId);
}
