package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PmsReservationGuest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PmsReservationGuestRepository extends JpaRepository<PmsReservationGuest, Long> {
    List<PmsReservationGuest> findByReservationIdOrderByIdAsc(Long reservationId);

    void deleteByReservationId(Long reservationId);
}
