package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.BookingCalendar;
import com.stackwizard.booking_api.repository.BookingCalendarRepository;
import com.stackwizard.booking_api.repository.LocationNodeRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Service
public class ServiceCalendarService {
    private final BookingCalendarRepository repo;
    private final LocationNodeRepository locationNodeRepo;

    public ServiceCalendarService(BookingCalendarRepository repo,
                                  LocationNodeRepository locationNodeRepo) {
        this.repo = repo;
        this.locationNodeRepo = locationNodeRepo;
    }

    public BookingCalendar calendarFor(Long tenantId, Long locationNodeId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (locationNodeId != null) {
            return findByLocationOrParents(tenantId, locationNodeId);
        }
        return findPropertyOrGlobal(tenantId);
    }

    private BookingCalendar findByLocationOrParents(Long tenantId, Long locationNodeId) {
        Long currentNodeId = locationNodeId;
        Set<Long> visited = new HashSet<>();

        while (currentNodeId != null && visited.add(currentNodeId)) {
            var calendar = repo.findFirstByTenantIdAndLocationNodeId(tenantId, currentNodeId);
            if (calendar.isPresent()) {
                return calendar.get();
            }
            var node = locationNodeRepo.findById(currentNodeId).orElse(null);
            currentNodeId = (node != null && node.getParent() != null) ? node.getParent().getId() : null;
        }

        return findPropertyOrGlobal(tenantId);
    }

    private BookingCalendar findPropertyOrGlobal(Long tenantId) {
        return repo.findFirstByTenantIdForMissingNode(tenantId)
                .orElseThrow(() -> new IllegalStateException("No booking calendar configured for tenant " + tenantId));
    }

    public ServiceWindow windowFor(BookingCalendar calendar, LocalDate date) {
        LocalDateTime open = LocalDateTime.of(date, calendar.getOpenTime());
        LocalDateTime close = LocalDateTime.of(date, calendar.getCloseTime());
        return new ServiceWindow(open, close);
    }

    public record ServiceWindow(LocalDateTime open, LocalDateTime close) {}
}
