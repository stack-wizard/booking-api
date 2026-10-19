package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingOperaBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingOperaBlockRepository extends JpaRepository<BookingOperaBlock, Long> {
    Optional<BookingOperaBlock> findByBookingIdAndOperaHotelId(Long bookingId, Long operaHotelId);

    List<BookingOperaBlock> findByTenantIdAndBookingIdOrderByIdAsc(Long tenantId, Long bookingId);
}
