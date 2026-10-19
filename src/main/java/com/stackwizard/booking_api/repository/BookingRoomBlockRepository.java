package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingRoomBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingRoomBlockRepository extends JpaRepository<BookingRoomBlock, Long> {
    Optional<BookingRoomBlock> findByIdAndTenantId(Long id, Long tenantId);

    List<BookingRoomBlock> findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(Long tenantId, Long bookingId);

    boolean existsByTenantIdAndBookingIdAndRoomResourceIdIsNotNull(Long tenantId, Long bookingId);
}
