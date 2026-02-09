package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ReservationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRequestRepository extends JpaRepository<ReservationRequest, Long> {
}
