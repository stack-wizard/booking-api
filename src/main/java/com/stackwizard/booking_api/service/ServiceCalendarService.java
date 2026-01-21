package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.BookingCalendar;
import com.stackwizard.booking_api.repository.BookingCalendarRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class ServiceCalendarService {
    private final BookingCalendarRepository repo;

    public ServiceCalendarService(BookingCalendarRepository repo) { this.repo = repo; }

    public BookingCalendar calendarFor(Long tenantId, Long locationNodeId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (locationNodeId != null) {
            return repo.findFirstByTenantIdAndLocationNodeId(tenantId, locationNodeId)
                    .orElseGet(() -> repo.findFirstByTenantIdAndLocationNodeIsNull(tenantId)
                            .orElseThrow(() -> new IllegalStateException("No booking calendar configured")));
        }
        return repo.findFirstByTenantIdAndLocationNodeIsNull(tenantId)
                .orElseThrow(() -> new IllegalStateException("No booking calendar configured"));
    }

    public ServiceWindow windowFor(BookingCalendar calendar, LocalDate date) {
        LocalDateTime open = LocalDateTime.of(date, calendar.getOpenTime());
        LocalDateTime close = LocalDateTime.of(date, calendar.getCloseTime());
        return new ServiceWindow(open, close);
    }

    public record ServiceWindow(LocalDateTime open, LocalDateTime close) {}
}
