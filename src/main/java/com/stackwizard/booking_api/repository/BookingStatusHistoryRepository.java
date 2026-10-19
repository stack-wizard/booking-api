package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingStatusHistoryRepository extends JpaRepository<BookingStatusHistory, Long> {
    List<BookingStatusHistory> findByTenantIdAndBookingIdOrderByCreatedAtAscIdAsc(Long tenantId, Long bookingId);
}
