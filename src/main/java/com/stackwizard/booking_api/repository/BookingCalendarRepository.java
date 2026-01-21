package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingCalendarRepository extends JpaRepository<BookingCalendar, Long> {
    Optional<BookingCalendar> findFirstByTenantIdAndLocationNodeId(Long tenantId, Long locationNodeId);
    Optional<BookingCalendar> findFirstByTenantIdAndLocationNodeIsNull(Long tenantId);
}
