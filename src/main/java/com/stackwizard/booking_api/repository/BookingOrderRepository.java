package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingOrderRepository extends JpaRepository<BookingOrder, Long> {
    Optional<BookingOrder> findByIdAndTenantId(Long id, Long tenantId);

    List<BookingOrder> findByTenantIdAndBookingIdOrderByVersionDesc(Long tenantId, Long bookingId);
}
