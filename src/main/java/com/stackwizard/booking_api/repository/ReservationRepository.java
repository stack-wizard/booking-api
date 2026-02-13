package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByRequestId(Long requestId);
    boolean existsByRequestId(Long requestId);
}
