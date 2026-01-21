package com.stackwizard.booking_api.booking;

import com.stackwizard.booking_api.model.BookingCalendar;
import com.stackwizard.booking_api.service.ServiceCalendarService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;

@Service
public class BookingTranslationService {
    private final ServiceCalendarService calendarService;

    public BookingTranslationService(ServiceCalendarService calendarService) { this.calendarService = calendarService; }

    public TranslatedPeriod translate(BookingUom uom,
                                      int qty,
                                      Long tenantId,
                                      Long locationNodeId,
                                      LocalDate serviceDate,
                                      LocalTime startTime) {
        if (serviceDate == null) {
            throw new IllegalArgumentException("serviceDate is required");
        }
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }

        BookingCalendar calendar = calendarService.calendarFor(tenantId, locationNodeId);
        ServiceCalendarService.ServiceWindow window = calendarService.windowFor(calendar, serviceDate);

        LocalDateTime start;
        LocalDateTime end;

        if (uom == BookingUom.DAY) {
            if (qty != 1) {
                throw new IllegalArgumentException("DAY bookings must have qty = 1");
            }
            start = window.open();
            end = window.close();
        } else if (uom == BookingUom.HOUR) {
            if (startTime == null) {
                throw new IllegalArgumentException("startTime is required for HOUR bookings");
            }
            validateGrid(startTime, calendar.getGridMinutes());
            start = LocalDateTime.of(serviceDate, startTime);
            end = start.plusHours(qty);
            if (start.isBefore(window.open())) {
                throw new IllegalArgumentException("startTime before service window");
            }
            if (end.isAfter(window.close())) {
                throw new IllegalArgumentException("endTime after service window");
            }
        } else {
            throw new IllegalArgumentException("Unsupported uom");
        }

        if (!start.toLocalDate().equals(serviceDate) || !end.toLocalDate().equals(serviceDate)) {
            throw new IllegalArgumentException("Booking cannot cross service date");
        }

        validateDuration(start, end, calendar);

        return new TranslatedPeriod(start, end);
    }

    private void validateGrid(LocalTime startTime, Integer gridMinutes) {
        if (gridMinutes == null || gridMinutes <= 0) {
            return;
        }
        if (startTime.getSecond() != 0 || startTime.getNano() != 0) {
            throw new IllegalArgumentException("startTime must align to grid");
        }
        int minutes = startTime.getHour() * 60 + startTime.getMinute();
        if (minutes % gridMinutes != 0) {
            throw new IllegalArgumentException("startTime must align to grid");
        }
    }

    private void validateDuration(LocalDateTime start, LocalDateTime end, BookingCalendar calendar) {
        long durationMinutes = Duration.between(start, end).toMinutes();
        if (calendar.getMinDurationMinutes() != null && calendar.getMinDurationMinutes() > 0
                && durationMinutes < calendar.getMinDurationMinutes()) {
            throw new IllegalArgumentException("Duration below minimum");
        }
        if (calendar.getMaxDurationMinutes() != null && calendar.getMaxDurationMinutes() > 0
                && durationMinutes > calendar.getMaxDurationMinutes()) {
            throw new IllegalArgumentException("Duration above maximum");
        }
    }

    public record TranslatedPeriod(LocalDateTime start, LocalDateTime end) {}
}
