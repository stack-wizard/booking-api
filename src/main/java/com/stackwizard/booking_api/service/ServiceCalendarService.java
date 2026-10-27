package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.BookingCalendar;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.model.SeasonCalendar;
import com.stackwizard.booking_api.model.SeasonOperating;
import com.stackwizard.booking_api.repository.BookingCalendarRepository;
import com.stackwizard.booking_api.repository.LocationNodeRepository;
import com.stackwizard.booking_api.repository.SeasonCalendarRepository;
import com.stackwizard.booking_api.repository.SeasonOperatingRepository;
import com.stackwizard.booking_api.repository.SeasonRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ServiceCalendarService {
    private final BookingCalendarRepository repo;
    private final LocationNodeRepository locationNodeRepo;
    private final SeasonCalendarRepository seasonCalendarRepo;
    private final SeasonRepository seasonRepo;
    private final SeasonOperatingRepository operatingRepo;
    private final TenantHierarchy tenantHierarchy;

    @Autowired
    public ServiceCalendarService(BookingCalendarRepository repo,
                                  LocationNodeRepository locationNodeRepo,
                                  SeasonCalendarRepository seasonCalendarRepo,
                                  SeasonRepository seasonRepo,
                                  SeasonOperatingRepository operatingRepo,
                                  TenantHierarchy tenantHierarchy) {
        this.repo = repo;
        this.locationNodeRepo = locationNodeRepo;
        this.seasonCalendarRepo = seasonCalendarRepo;
        this.seasonRepo = seasonRepo;
        this.operatingRepo = operatingRepo;
        this.tenantHierarchy = tenantHierarchy;
    }

    /** Without seasons: windows come only from {@code booking_calendar}. */
    public ServiceCalendarService(BookingCalendarRepository repo, LocationNodeRepository locationNodeRepo) {
        this(repo, locationNodeRepo, null, null, null, null);
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

    /**
     * Opening hours on {@code date}: the season's hours ({@code season_operating}) for the calendar's location, then for
     * all locations; otherwise the {@code booking_calendar} hours as before.
     */
    public ServiceWindow windowFor(BookingCalendar calendar, LocalDate date) {
        Optional<SeasonOperating> operating = operatingFor(calendar, date);
        if (operating.isPresent()) {
            return new ServiceWindow(LocalDateTime.of(date, operating.get().getOpenTime()),
                    LocalDateTime.of(date, operating.get().getCloseTime()));
        }
        LocalDateTime open = LocalDateTime.of(date, calendar.getOpenTime());
        LocalDateTime close = LocalDateTime.of(date, calendar.getCloseTime());
        return new ServiceWindow(open, close);
    }

    /**
     * Season hours that apply to the booking calendar on {@code date}. Season calendars of the hotel are checked before
     * the chain's; within the first season that has hours, the calendar's location wins over "all locations".
     */
    public Optional<SeasonOperating> operatingFor(BookingCalendar calendar, LocalDate date) {
        if (seasonCalendarRepo == null || calendar == null || date == null || calendar.getTenantId() == null) {
            return Optional.empty();
        }
        Long property = calendar.getTenantId();
        Long org = tenantHierarchy != null ? tenantHierarchy.orgIdOf(property) : property;
        List<SeasonCalendar> calendars = seasonCalendarRepo.findApplicable(property, org);
        if (calendars.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Season> seasonByCalendar = seasonRepo.findOnDateIn(
                        calendars.stream().map(SeasonCalendar::getId).toList(), date).stream()
                .collect(Collectors.toMap(Season::getSeasonCalendarId, Function.identity(), (a, b) -> a));
        Long locationId = calendar.getLocationNode() != null ? calendar.getLocationNode().getId() : null;
        for (SeasonCalendar sc : calendars) {
            Season season = seasonByCalendar.get(sc.getId());
            if (season == null) {
                continue;
            }
            List<SeasonOperating> rows = operatingRepo.findBySeasonIdOrderByLocationNodeIdAscIdAsc(season.getId());
            Optional<SeasonOperating> forLocation = locationId == null ? Optional.empty()
                    : rows.stream().filter(r -> Objects.equals(r.getLocationNodeId(), locationId)).findFirst();
            Optional<SeasonOperating> any = forLocation.or(() -> rows.stream().filter(r -> r.getLocationNodeId() == null).findFirst());
            if (any.isPresent()) {
                return any;
            }
        }
        return Optional.empty();
    }

    /**
     * Grid and min/max duration on {@code date}: the season's values ({@code season_operating}) where set, otherwise
     * the {@code booking_calendar} values as before.
     */
    public DayRules rulesFor(BookingCalendar calendar, LocalDate date) {
        Optional<SeasonOperating> operating = operatingFor(calendar, date);
        return new DayRules(
                operating.map(SeasonOperating::getGridMinutes).orElse(calendar.getGridMinutes()),
                operating.map(SeasonOperating::getMinDurationMinutes).orElse(calendar.getMinDurationMinutes()),
                operating.map(SeasonOperating::getMaxDurationMinutes).orElse(calendar.getMaxDurationMinutes()));
    }

    public record ServiceWindow(LocalDateTime open, LocalDateTime close) {}

    public record DayRules(Integer gridMinutes, Integer minDurationMinutes, Integer maxDurationMinutes) {}
}
