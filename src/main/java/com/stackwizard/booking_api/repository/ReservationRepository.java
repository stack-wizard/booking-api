package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
