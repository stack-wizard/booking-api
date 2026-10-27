package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.SeasonDtos;
import com.stackwizard.booking_api.model.LocationNode;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.model.SeasonCalendar;
import com.stackwizard.booking_api.model.SeasonOperating;
import com.stackwizard.booking_api.repository.LocationNodeRepository;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.PriceProfileDateRepository;
import com.stackwizard.booking_api.repository.SalesCatalogRepository;
import com.stackwizard.booking_api.repository.SeasonCalendarRepository;
import com.stackwizard.booking_api.repository.SeasonOperatingRepository;
import com.stackwizard.booking_api.repository.SeasonRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Season calendars, seasons (no overlap inside a calendar), season opening hours and "copy year".
 * Seasons belong to the chain; a calendar may be limited to one hotel.
 */
@Service
public class SeasonService {
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_\\-]{0,49}$");
    private static final BigDecimal MIN_PERCENT = new BigDecimal("-90");
    private static final BigDecimal MAX_PERCENT = new BigDecimal("500");

    private final SeasonCalendarRepository calendarRepo;
    private final SeasonRepository seasonRepo;
    private final SeasonOperatingRepository operatingRepo;
    private final SalesCatalogRepository catalogRepo;
    private final PriceProfileDateRepository periodRepo;
    private final PriceListEntryRepository priceRepo;
    private final LocationNodeRepository locationRepo;
    private final TenantHierarchy tenantHierarchy;
    private final PricingAccess access;

    public SeasonService(SeasonCalendarRepository calendarRepo,
                         SeasonRepository seasonRepo,
                         SeasonOperatingRepository operatingRepo,
                         SalesCatalogRepository catalogRepo,
                         PriceProfileDateRepository periodRepo,
                         PriceListEntryRepository priceRepo,
                         LocationNodeRepository locationRepo,
                         TenantHierarchy tenantHierarchy,
                         PricingAccess access) {
        this.calendarRepo = calendarRepo;
        this.seasonRepo = seasonRepo;
        this.operatingRepo = operatingRepo;
        this.catalogRepo = catalogRepo;
        this.periodRepo = periodRepo;
        this.priceRepo = priceRepo;
        this.locationRepo = locationRepo;
        this.tenantHierarchy = tenantHierarchy;
        this.access = access;
    }

    // ---------------------------------------------------------------- calendars

    @Transactional(readOnly = true)
    public List<SeasonDtos.CalendarRow> calendars() {
        access.requireRead();
        Long tenantId = TenantResolver.requireOrgTenantId();
        return calendarRepo.findByTenantIdOrderByNameAscIdAsc(tenantId).stream().map(this::toRow).toList();
    }

    @Transactional
    public SeasonDtos.CalendarRow createCalendar(SeasonDtos.CalendarInput input) {
        access.requireWrite("seasons");
        Long tenantId = TenantResolver.requireOrgTenantId();
        if (input == null) {
            throw new IllegalArgumentException("name is required");
        }
        String name = requireText(input.name(), "name");
        Long property = input.propertyTenantId();
        if (property != null) {
            tenantHierarchy.requirePropertyOf(property, tenantId);
        }
        requireUniqueCalendarName(tenantId, property, name, null);
        SeasonCalendar saved = calendarRepo.save(SeasonCalendar.builder()
                .tenantId(tenantId)
                .propertyTenantId(property)
                .name(name)
                .build());
        return toRow(saved);
    }

    @Transactional
    public SeasonDtos.CalendarRow renameCalendar(Long id, SeasonDtos.CalendarInput input) {
        access.requireWrite("seasons");
        SeasonCalendar calendar = requireCalendar(id);
        String name = requireText(input == null ? null : input.name(), "name");
        requireUniqueCalendarName(calendar.getTenantId(), calendar.getPropertyTenantId(), name, calendar.getId());
        calendar.setName(name);
        return toRow(calendarRepo.save(calendar));
    }

    @Transactional
    public void deleteCalendar(Long id) {
        access.requireWrite("seasons");
        SeasonCalendar calendar = requireCalendar(id);
        List<String> catalogs = usedBy(calendar.getId());
        if (!catalogs.isEmpty()) {
            throw new IllegalStateException("Season calendar '" + calendar.getName() + "' is used by catalog(s) "
                    + String.join(", ", catalogs) + "; pick another calendar there first");
        }
        for (Season season : seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(calendar.getId())) {
            periodRepo.unlinkSeason(season.getId());
        }
        calendarRepo.delete(calendar);
    }

    // ---------------------------------------------------------------- seasons

    @Transactional(readOnly = true)
    public List<SeasonDtos.SeasonRow> seasons(Long calendarId, Integer year) {
        access.requireRead();
        SeasonCalendar calendar = requireCalendar(calendarId);
        List<Season> seasons = seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(calendar.getId());
        if (year != null) {
            LocalDate from = LocalDate.of(year, 1, 1);
            LocalDate to = LocalDate.of(year, 12, 31);
            seasons = seasons.stream().filter(s -> !s.getDateTo().isBefore(from) && !s.getDateFrom().isAfter(to)).toList();
        }
        return seasons.stream().map(this::toRow).toList();
    }

    @Transactional
    public SeasonDtos.SeasonRow createSeason(Long calendarId, SeasonDtos.SeasonInput input) {
        access.requireWrite("seasons");
        SeasonCalendar calendar = requireCalendar(calendarId);
        Season season = Season.builder().tenantId(calendar.getTenantId()).seasonCalendarId(calendar.getId()).build();
        apply(season, input);
        requireNoConflict(season);
        return toRow(seasonRepo.saveAndFlush(season));
    }

    @Transactional
    public SeasonDtos.SeasonRow updateSeason(Long id, SeasonDtos.SeasonInput input) {
        access.requireWrite("seasons");
        Season season = requireSeason(id);
        apply(season, input);
        requireNoConflict(season);
        List<String> outside = periodRepo.findBySeasonIds(List.of(season.getId())).stream()
                .filter(p -> p.getPeriodKind() != PriceProfileDate.PeriodKind.SEASON)
                .filter(p -> p.getDateFrom().isBefore(season.getDateFrom()) || p.getDateTo().isAfter(season.getDateTo()))
                .map(p -> describe(p))
                .toList();
        if (!outside.isEmpty()) {
            throw new IllegalStateException("These price periods of the season would fall outside the new dates: "
                    + String.join(", ", outside) + "; change or unlink them first");
        }
        return toRow(seasonRepo.saveAndFlush(season));
    }

    /** Deleting a season unlinks its price periods ({@code season_id = null}); the prices stay. */
    @Transactional
    public void deleteSeason(Long id) {
        access.requireWrite("seasons");
        Season season = requireSeason(id);
        periodRepo.unlinkSeason(season.getId());
        seasonRepo.delete(season);
    }

    @Transactional(readOnly = true)
    public List<SeasonDtos.Operating> operating(Long seasonId) {
        access.requireRead();
        Season season = requireSeason(seasonId);
        return operatingRepo.findBySeasonIdOrderByLocationNodeIdAscIdAsc(season.getId()).stream()
                .map(SeasonService::toOperating).toList();
    }

    /** Replaces the whole list of the season's hours. */
    @Transactional
    public List<SeasonDtos.Operating> replaceOperating(Long seasonId, List<SeasonDtos.Operating> rows) {
        access.requireWrite("seasons");
        Season season = requireSeason(seasonId);
        List<SeasonDtos.Operating> input = rows == null ? List.of() : rows;
        Set<Long> seen = new HashSet<>();
        boolean allLocations = false;
        List<SeasonOperating> toSave = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) {
            SeasonDtos.Operating row = input.get(i);
            String at = "operating[" + i + "]";
            if (row == null) {
                throw new IllegalArgumentException(at + " must not be null");
            }
            if (row.locationNodeId() == null) {
                if (allLocations) {
                    throw new IllegalArgumentException(at + ": hours for all locations are given twice");
                }
                allLocations = true;
            } else {
                if (!seen.add(row.locationNodeId())) {
                    throw new IllegalArgumentException(at + ": location " + row.locationNodeId() + " is given twice");
                }
                requireLocation(row.locationNodeId(), at);
            }
            if (row.openTime() == null || row.closeTime() == null) {
                throw new IllegalArgumentException(at + ": openTime and closeTime are required");
            }
            if (!row.closeTime().isAfter(row.openTime())) {
                throw new IllegalArgumentException(at + ": closeTime must be after openTime");
            }
            if (row.gridMinutes() == null || row.gridMinutes() <= 0) {
                throw new IllegalArgumentException(at + ": gridMinutes must be > 0");
            }
            int min = row.minDurationMinutes() == null ? 0 : row.minDurationMinutes();
            int max = row.maxDurationMinutes() == null ? 0 : row.maxDurationMinutes();
            if (min < 0 || max < 0) {
                throw new IllegalArgumentException(at + ": minDurationMinutes and maxDurationMinutes must be 0 or more");
            }
            if (max > 0 && max < min) {
                throw new IllegalArgumentException(at + ": maxDurationMinutes must be >= minDurationMinutes (0 = no limit)");
            }
            toSave.add(SeasonOperating.builder()
                    .tenantId(season.getTenantId())
                    .seasonId(season.getId())
                    .locationNodeId(row.locationNodeId())
                    .openTime(row.openTime())
                    .closeTime(row.closeTime())
                    .gridMinutes(row.gridMinutes())
                    .minDurationMinutes(min)
                    .maxDurationMinutes(max)
                    .build());
        }
        operatingRepo.deleteBySeasonId(season.getId());
        operatingRepo.flush();
        operatingRepo.saveAll(toSave);
        return operating(seasonId);
    }

    // ---------------------------------------------------------------- copy year

    @Transactional
    public SeasonDtos.CopyYearResult copyYear(Long calendarId, SeasonDtos.CopyYearRequest request) {
        access.requireWrite("seasons");
        SeasonCalendar calendar = requireCalendar(calendarId);
        if (request == null || request.fromYear() == null || request.toYear() == null) {
            throw new IllegalArgumentException("fromYear and toYear are required");
        }
        int fromYear = request.fromYear();
        int toYear = request.toYear();
        if (fromYear == toYear) {
            throw new IllegalArgumentException("toYear must differ from fromYear");
        }
        if (fromYear < 2000 || fromYear > 2100 || toYear < 2000 || toYear > 2100) {
            throw new IllegalArgumentException("fromYear and toYear must be between 2000 and 2100");
        }
        boolean sameWeekday = request.shiftToSameWeekday() == null || request.shiftToSameWeekday();
        boolean copyPrices = request.copyPrices() == null || request.copyPrices();
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        BigDecimal factor = BigDecimal.ONE;
        if (request.pricePercent() != null) {
            if (request.pricePercent().compareTo(MIN_PERCENT) < 0 || request.pricePercent().compareTo(MAX_PERCENT) > 0) {
                throw new IllegalArgumentException("pricePercent must be between -90 and 500");
            }
            factor = BigDecimal.ONE.add(request.pricePercent().movePointLeft(2));
        }
        DateShift shift = new DateShift(fromYear, toYear, sameWeekday);

        List<Season> all = seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(calendar.getId());
        List<Season> source = all.stream().filter(s -> s.getDateFrom().getYear() == fromYear).toList();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Calendar '" + calendar.getName() + "' has no season starting in " + fromYear);
        }
        Set<String> codes = new HashSet<>();
        all.forEach(s -> codes.add(s.getCode()));
        List<Season> occupied = new ArrayList<>(all);

        List<SeasonDtos.PlannedSeason> planned = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int operatingCount = 0;
        int periodCount = 0;
        int priceCount = 0;
        for (Season s : source) {
            String code = retag(s.getCode(), fromYear, toYear, true);
            String name = retag(s.getName(), fromYear, toYear, false);
            LocalDate from = shift.apply(s.getDateFrom());
            LocalDate to = shift.apply(s.getDateTo());
            if (codes.contains(code)) {
                skipped.add("Season " + s.getCode() + ": code " + code + " already exists");
                continue;
            }
            Optional<Season> clash = occupied.stream()
                    .filter(o -> !o.getDateTo().isBefore(from) && !o.getDateFrom().isAfter(to))
                    .findFirst();
            if (clash.isPresent()) {
                skipped.add("Season " + s.getCode() + ": " + from + " - " + to + " overlaps '" + clash.get().getName()
                        + "' (" + clash.get().getCode() + ")");
                continue;
            }
            codes.add(code);
            Season copy = Season.builder()
                    .tenantId(s.getTenantId())
                    .seasonCalendarId(calendar.getId())
                    .code(code)
                    .name(name)
                    .dateFrom(from)
                    .dateTo(to)
                    .status(s.getStatus())
                    .displayOrder(s.getDisplayOrder())
                    .note(s.getNote())
                    .build();
            occupied.add(copy);
            planned.add(new SeasonDtos.PlannedSeason(code, name, from, to, s.getId()));
            if (!dryRun) {
                copy = seasonRepo.saveAndFlush(copy);
            }
            List<SeasonOperating> hours = operatingRepo.findBySeasonIdOrderByLocationNodeIdAscIdAsc(s.getId());
            operatingCount += hours.size();
            if (!dryRun) {
                for (SeasonOperating h : hours) {
                    operatingRepo.save(SeasonOperating.builder()
                            .tenantId(h.getTenantId())
                            .seasonId(copy.getId())
                            .locationNodeId(h.getLocationNodeId())
                            .openTime(h.getOpenTime())
                            .closeTime(h.getCloseTime())
                            .gridMinutes(h.getGridMinutes())
                            .minDurationMinutes(h.getMinDurationMinutes())
                            .maxDurationMinutes(h.getMaxDurationMinutes())
                            .build());
                }
            }
            List<PriceProfileDate> periods = periodRepo.findBySeasonIds(List.of(s.getId()));
            Map<Long, PriceProfileDate> newPeriods = new HashMap<>();
            for (PriceProfileDate p : periods) {
                periodCount++;
                PriceProfileDate np = PriceProfileDate.builder()
                        .priceProfile(p.getPriceProfile())
                        .dateFrom(shift.apply(p.getDateFrom()))
                        .dateTo(shift.apply(p.getDateTo()))
                        .description(p.getDescription() == null ? null : retag(p.getDescription(), fromYear, toYear, false))
                        .periodKind(p.getPeriodKind())
                        .seasonId(copy.getId())
                        .build();
                if (!dryRun) {
                    np = periodRepo.save(np);
                }
                newPeriods.put(p.getId(), np);
            }
            if (copyPrices && !periods.isEmpty()) {
                for (PriceListEntry e : priceRepo.findByPeriodIds(newPeriods.keySet())) {
                    priceCount++;
                    if (dryRun) {
                        continue;
                    }
                    PriceProfileDate target = newPeriods.get(e.getPriceProfileDate().getId());
                    priceRepo.save(PriceListEntry.builder()
                            .productId(e.getProductId())
                            .uom(e.getUom())
                            .price(ProductPriceService.round(e.getPrice().multiply(factor), request.roundTo()))
                            .startTime(e.getStartTime())
                            .endTime(e.getEndTime())
                            .daysOfWeek(e.getDaysOfWeek())
                            .priceProfile(target.getPriceProfile())
                            .priceProfileDate(target)
                            .build());
                }
            }
        }
        return new SeasonDtos.CopyYearResult(dryRun, planned.size(), operatingCount, periodCount, priceCount, planned, skipped);
    }

    /**
     * Same weekday: shift by whole weeks closest to the year difference (52 weeks for one year), so every copied date
     * keeps its weekday and seasons keep their length and gaps. Otherwise the same calendar date.
     */
    record DateShift(int fromYear, int toYear, boolean sameWeekday) {
        LocalDate apply(LocalDate date) {
            if (!sameWeekday) {
                return date.plusYears(toYear - fromYear);
            }
            LocalDate sameDate = LocalDate.of(fromYear, 1, 1).plusYears(toYear - fromYear);
            long days = ChronoUnit.DAYS.between(LocalDate.of(fromYear, 1, 1), sameDate);
            long weeks = Math.round(days / 7.0);
            return date.plusWeeks(weeks);
        }
    }

    static String retag(String value, int fromYear, int toYear, boolean code) {
        String from = String.valueOf(fromYear);
        if (value.contains(from)) {
            return value.replace(from, String.valueOf(toYear));
        }
        String fromShort = from.substring(2);
        if (code && value.endsWith("_" + fromShort)) {
            return value.substring(0, value.length() - 2) + String.valueOf(toYear).substring(2);
        }
        return code ? value + "_" + toYear : value + " " + toYear;
    }

    // ---------------------------------------------------------------- lookups used by catalogs

    /** Season of the calendar on the date, if any. */
    @Transactional(readOnly = true)
    public Optional<Season> seasonOn(Long calendarId, LocalDate date) {
        if (calendarId == null || date == null) {
            return Optional.empty();
        }
        return seasonRepo.findOnDate(calendarId, date);
    }

    // ---------------------------------------------------------------- helpers

    private void apply(Season season, SeasonDtos.SeasonInput input) {
        if (input == null) {
            throw new IllegalArgumentException("code, name, dateFrom and dateTo are required");
        }
        String code = requireText(input.code(), "code").toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code may contain only letters, digits, '_' and '-' (max 50), e.g. HIGH_2027");
        }
        String name = requireText(input.name(), "name");
        if (input.dateFrom() == null || input.dateTo() == null) {
            throw new IllegalArgumentException("dateFrom and dateTo are required");
        }
        if (input.dateTo().isBefore(input.dateFrom())) {
            throw new IllegalArgumentException("dateTo must not be before dateFrom");
        }
        season.setCode(code);
        season.setName(name);
        season.setDateFrom(input.dateFrom());
        season.setDateTo(input.dateTo());
        season.setStatus(input.status() != null ? input.status() : Season.Status.OPEN);
        season.setDisplayOrder(input.displayOrder() != null ? input.displayOrder() : 0);
        season.setNote(input.note() == null || input.note().isBlank() ? null : input.note().trim());
    }

    private void requireNoConflict(Season season) {
        for (Season other : seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(season.getSeasonCalendarId())) {
            if (Objects.equals(other.getId(), season.getId())) {
                continue;
            }
            if (other.getCode().equalsIgnoreCase(season.getCode())) {
                throw new IllegalStateException("Season code " + season.getCode() + " already exists in this calendar");
            }
            if (!other.getDateTo().isBefore(season.getDateFrom()) && !other.getDateFrom().isAfter(season.getDateTo())) {
                throw new IllegalStateException("Season overlaps '" + other.getName() + "' (" + other.getCode() + ", "
                        + other.getDateFrom() + " - " + other.getDateTo() + ")");
            }
        }
    }

    private void requireUniqueCalendarName(Long tenantId, Long property, String name, Long selfId) {
        boolean exists = calendarRepo.findByTenantIdOrderByNameAscIdAsc(tenantId).stream()
                .anyMatch(c -> !Objects.equals(c.getId(), selfId)
                        && Objects.equals(c.getPropertyTenantId(), property)
                        && c.getName().equalsIgnoreCase(name));
        if (exists) {
            throw new IllegalStateException("A season calendar named '" + name + "' already exists");
        }
    }

    private void requireLocation(Long locationId, String at) {
        LocationNode node = locationRepo.findById(locationId)
                .orElseThrow(() -> new IllegalArgumentException(at + ": location " + locationId + " not found"));
        Long org = TenantResolver.requireOrgTenantId();
        if (node.getTenantId() != null && !org.equals(node.getTenantId()) && !tenantHierarchy.isPropertyOf(node.getTenantId(), org)) {
            throw new IllegalArgumentException(at + ": location " + locationId + " not found");
        }
    }

    SeasonCalendar requireCalendar(Long id) {
        Long tenantId = TenantResolver.requireOrgTenantId();
        return calendarRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season calendar not found: " + id));
    }

    Season requireSeason(Long id) {
        Long tenantId = TenantResolver.requireOrgTenantId();
        return seasonRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id));
    }

    private List<String> usedBy(Long calendarId) {
        return catalogRepo.findBySeasonCalendarId(calendarId).stream().map(SalesCatalog::getCode).sorted().toList();
    }

    private SeasonDtos.CalendarRow toRow(SeasonCalendar c) {
        return new SeasonDtos.CalendarRow(c.getId(), c.getName(), c.getPropertyTenantId(),
                seasonRepo.countBySeasonCalendarId(c.getId()), usedBy(c.getId()));
    }

    private SeasonDtos.SeasonRow toRow(Season s) {
        List<SeasonDtos.Operating> hours = s.getId() == null ? List.of()
                : operatingRepo.findBySeasonIdOrderByLocationNodeIdAscIdAsc(s.getId()).stream().map(SeasonService::toOperating).toList();
        return new SeasonDtos.SeasonRow(s.getId(), s.getSeasonCalendarId(), s.getCode(), s.getName(), s.getDateFrom(),
                s.getDateTo(), s.getStatus(), s.getDisplayOrder(), s.getNote(), hours,
                s.getId() == null ? 0 : periodRepo.countBySeasonId(s.getId()));
    }

    private static SeasonDtos.Operating toOperating(SeasonOperating o) {
        return new SeasonDtos.Operating(o.getLocationNodeId(), o.getOpenTime(), o.getCloseTime(), o.getGridMinutes(),
                o.getMinDurationMinutes(), o.getMaxDurationMinutes());
    }

    private static String describe(PriceProfileDate p) {
        String label = p.getDescription() != null ? p.getDescription() : p.getPeriodKind().name();
        return label + " (" + p.getDateFrom() + " - " + p.getDateTo() + ")";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
