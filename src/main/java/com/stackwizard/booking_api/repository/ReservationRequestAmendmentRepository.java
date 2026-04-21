package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ReservationRequestAmendment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRequestAmendmentRepository extends JpaRepository<ReservationRequestAmendment, Long> {
}
