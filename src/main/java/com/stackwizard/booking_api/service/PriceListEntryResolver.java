package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.PriceProfile;
import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.SeasonRepository;
import com.stackwizard.booking_api.support.DaysOfWeek;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picks the effective price per key <i>product + UOM + currency + slot</i>. Candidates are prices whose period
 * contains the date and whose {@code days_of_week} (when set) contains the weekday. Precedence, most important first:
 * <ol>
 *   <li>level: the hotel's own price before the chain price</li>
 *   <li>channel: matching {@code reservation_request_type} before a generic profile</li>
 *   <li>period kind: DATES before SUB_PERIOD before SEASON</li>
 *   <li>weekday: a price with {@code days_of_week} before one without</li>
 *   <li>tie-break: shorter period, then newer {@code created_at}, then lower id</li>
 * </ol>
 * Existing data (all SEASON, no weekdays, no overlapping periods on one level) resolves exactly as before.
 */
@Service
public class PriceListEntryResolver {
    private final PriceListEntryRepository priceListRepo;
    private final TenantHierarchy tenantHierarchy;
    private final SeasonRepository seasonRepo;

    @Autowired
    public PriceListEntryResolver(PriceListEntryRepository priceListRepo,
                                  TenantHierarchy tenantHierarchy,
                                  SeasonRepository seasonRepo) {
        this.priceListRepo = priceListRepo;
        this.tenantHierarchy = tenantHierarchy;
        this.seasonRepo = seasonRepo;
    }

    /** Without season lookups (rule.season stays null); for tests and callers that need only the price. */
    public PriceListEntryResolver(PriceListEntryRepository priceListRepo, TenantHierarchy tenantHierarchy) {
        this(priceListRepo, tenantHierarchy, null);
    }

    @Transactional(readOnly = true)
    public List<PriceListEntry> findEffectiveForProductsOnDate(List<Long> productIds,
                                                               Long tenantId,
                                                               LocalDate date,
                                                               ReservationRequest.Type requestType) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<PriceListEntry> candidates = priceListRepo.findCandidatesForProductsOnDate(
                productIds,
                tenantHierarchy.orgIdOf(tenantId),
                tenantId,
                date,
                normalizeRequestType(requestType)
        );
        return mergeCandidates(candidates, normalizeRequestType(requestType), date);
    }

    @Transactional(readOnly = true)
    public List<PriceListEntry> findEffectiveForProductUomOnDate(Long productId,
                                                                 String uom,
                                                                 String currency,
                                                                 Long tenantId,
                                                                 LocalDate date,
                                                                 ReservationRequest.Type requestType) {
        List<PriceListEntry> candidates = candidates(productId, uom, currency, tenantId, date, requestType);
        return mergeCandidates(candidates, normalizeRequestType(requestType), date);
    }

    /**
     * One price for product + UOM on a date: the slot containing {@code time} when given, otherwise the price without
     * a slot, otherwise the first slot. Empty when the product has no price that day.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ResolvedPrice> resolve(Long productId,
                                                     String uom,
                                                     String currency,
                                                     Long tenantId,
                                                     LocalDate date,
                                                     LocalTime time,
                                                     ReservationRequest.Type requestType) {
        List<PriceListEntry> effective = findEffectiveForProductUomOnDate(productId, uom, currency, tenantId, date, requestType);
        PriceListEntry picked = pickSlot(effective, time);
        if (picked == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ResolvedPrice(picked, ruleOf(picked, tenantId, requestType)));
    }

    /**
     * "Why is this the price": the winner for the slot and every other candidate of product + UOM with the reason it
     * lost (other slot, weekday, or which precedence step).
     */
    @Transactional(readOnly = true)
    public PriceCheck explain(Long productId,
                              String uom,
                              String currency,
                              Long tenantId,
                              LocalDate date,
                              LocalTime time,
                              ReservationRequest.Type requestType) {
        ReservationRequest.Type type = normalizeRequestType(requestType);
        List<PriceListEntry> all = candidates(productId, uom, currency, tenantId, date, type);
        List<PriceListEntry> effective = mergeCandidates(all, type, date);
        PriceListEntry winner = pickSlot(effective, time);
        Map<Long, String> seasonCodes = seasonCodes(all);
        List<Candidate> out = new ArrayList<>();
        for (PriceListEntry e : all) {
            if (e == winner) {
                continue;
            }
            String reason;
            if (!DaysOfWeek.matches(e.getDaysOfWeek(), date)) {
                reason = "Not valid on " + date.getDayOfWeek().name().substring(0, 3);
            } else if (winner == null) {
                reason = null;
            } else if (!EntryKey.from(e).equals(EntryKey.from(winner))) {
                reason = "Other time slot";
            } else {
                reason = lostBecause(e, winner, type);
            }
            out.add(new Candidate(e.getPrice(), ruleOf(e, tenantId, type, seasonCodes), reason));
        }
        Winner w = winner == null ? null : new Winner(winner.getPrice(), currency(winner), ruleOf(winner, tenantId, type, seasonCodes));
        return new PriceCheck(w, out);
    }

    public AppliedRule ruleOf(PriceListEntry entry, Long tenantId, ReservationRequest.Type requestType) {
        return ruleOf(entry, tenantId, requestType, seasonCodes(List.of(entry)));
    }

    private List<PriceListEntry> candidates(Long productId, String uom, String currency, Long tenantId,
                                            LocalDate date, ReservationRequest.Type requestType) {
        return priceListRepo.findCandidatesForProductUomOnDate(
                productId,
                uom,
                currency,
                tenantHierarchy.orgIdOf(tenantId),
                tenantId,
                date,
                normalizeRequestType(requestType)
        );
    }

    private ReservationRequest.Type normalizeRequestType(ReservationRequest.Type requestType) {
        return requestType != null ? requestType : ReservationRequest.Type.EXTERNAL;
    }

    private List<PriceListEntry> mergeCandidates(List<PriceListEntry> candidates,
                                                 ReservationRequest.Type requestType,
                                                 LocalDate date) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Comparator<PriceListEntry> precedence = precedence(requestType);
        Map<EntryKey, PriceListEntry> effective = new LinkedHashMap<>();
        for (PriceListEntry candidate : candidates) {
            if (!DaysOfWeek.matches(candidate.getDaysOfWeek(), date)) {
                continue;
            }
            EntryKey key = EntryKey.from(candidate);
            PriceListEntry current = effective.get(key);
            if (current == null || precedence.compare(candidate, current) < 0) {
                effective.put(key, candidate);
            }
        }
        return new ArrayList<>(effective.values());
    }

    /** Smaller = wins. */
    static Comparator<PriceListEntry> precedence(ReservationRequest.Type requestType) {
        return Comparator.<PriceListEntry>comparingInt(PriceListEntryResolver::levelRank)
                .thenComparingInt(e -> channelRank(e, requestType))
                .thenComparingInt(PriceListEntryResolver::kindRank)
                .thenComparingInt(e -> e.getDaysOfWeek() != null ? 0 : 1)
                .thenComparingLong(PriceListEntryResolver::periodDays)
                .thenComparing(PriceListEntry::getCreatedAt, Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder()))
                .thenComparing(PriceListEntry::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static int levelRank(PriceListEntry e) {
        PriceProfile profile = e.getPriceProfile();
        return profile != null && profile.getPropertyTenantId() != null ? 0 : 1;
    }

    private static int channelRank(PriceListEntry e, ReservationRequest.Type requestType) {
        PriceProfile profile = e.getPriceProfile();
        return profile != null && profile.getReservationRequestType() == requestType ? 0 : 1;
    }

    private static int kindRank(PriceListEntry e) {
        PriceProfileDate.PeriodKind kind = kind(e);
        return switch (kind) {
            case DATES -> 0;
            case SUB_PERIOD -> 1;
            case SEASON -> 2;
        };
    }

    private static PriceProfileDate.PeriodKind kind(PriceListEntry e) {
        PriceProfileDate period = e.getPriceProfileDate();
        return period != null && period.getPeriodKind() != null ? period.getPeriodKind() : PriceProfileDate.PeriodKind.SEASON;
    }

    private static long periodDays(PriceListEntry e) {
        PriceProfileDate period = e.getPriceProfileDate();
        if (period == null || period.getDateFrom() == null || period.getDateTo() == null) {
            return Long.MAX_VALUE;
        }
        return ChronoUnit.DAYS.between(period.getDateFrom(), period.getDateTo());
    }

    private static String lostBecause(PriceListEntry loser, PriceListEntry winner, ReservationRequest.Type type) {
        if (levelRank(loser) != levelRank(winner)) {
            return "Hotel price wins over chain price";
        }
        if (channelRank(loser, type) != channelRank(winner, type)) {
            return "Price for channel " + type + " wins";
        }
        if (kindRank(loser) != kindRank(winner)) {
            return kind(winner) + " period wins over " + kind(loser);
        }
        if ((loser.getDaysOfWeek() == null) != (winner.getDaysOfWeek() == null)) {
            return "Weekday price wins over every-day price";
        }
        if (periodDays(loser) != periodDays(winner)) {
            return "Shorter period wins";
        }
        return "Newer price wins";
    }

    private static PriceListEntry pickSlot(List<PriceListEntry> effective, LocalTime time) {
        if (effective.isEmpty()) {
            return null;
        }
        if (time != null) {
            for (PriceListEntry e : effective) {
                if (e.getStartTime() != null && e.getEndTime() != null
                        && !time.isBefore(e.getStartTime()) && time.isBefore(e.getEndTime())) {
                    return e;
                }
            }
        }
        return effective.stream().filter(e -> e.getStartTime() == null).findFirst().orElse(effective.get(0));
    }

    private Map<Long, String> seasonCodes(List<PriceListEntry> entries) {
        if (seasonRepo == null) {
            return Map.of();
        }
        Set<Long> ids = entries.stream()
                .map(PriceListEntry::getPriceProfileDate)
                .filter(Objects::nonNull)
                .map(PriceProfileDate::getSeasonId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> out = new HashMap<>();
        for (Season s : seasonRepo.findByIdIn(ids)) {
            out.put(s.getId(), s.getCode());
        }
        return out;
    }

    private AppliedRule ruleOf(PriceListEntry e, Long tenantId, ReservationRequest.Type requestType, Map<Long, String> seasonCodes) {
        PriceProfile profile = e.getPriceProfile();
        PriceProfileDate period = e.getPriceProfileDate();
        Long seasonId = period != null ? period.getSeasonId() : null;
        return new AppliedRule(
                e.getId(),
                profile != null ? profile.getId() : null,
                profile != null ? profile.getName() : null,
                levelRank(e) == 0 ? "PROPERTY" : "CHAIN",
                profile != null ? profile.getReservationRequestType() : null,
                period != null ? period.getId() : null,
                period != null ? period.getDateFrom() : null,
                period != null ? period.getDateTo() : null,
                period != null ? period.getDescription() : null,
                kind(e),
                seasonId,
                seasonId != null ? seasonCodes.get(seasonId) : null,
                DaysOfWeek.label(e.getDaysOfWeek()),
                e.getStartTime(),
                e.getEndTime());
    }

    private static String currency(PriceListEntry e) {
        return e.getPriceProfile() != null ? e.getPriceProfile().getCurrency() : null;
    }

    /** The winning price and why. */
    public record ResolvedPrice(PriceListEntry entry, AppliedRule rule) {
        public BigDecimal price() {
            return entry.getPrice();
        }
    }

    /** Which price-list row applied: profile, level, channel, period (kind, season) and weekdays. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppliedRule(Long priceId,
                              Long priceProfileId,
                              String priceProfile,
                              String level,
                              ReservationRequest.Type requestType,
                              Long periodId,
                              LocalDate periodFrom,
                              LocalDate periodTo,
                              String periodDescription,
                              PriceProfileDate.PeriodKind periodKind,
                              Long seasonId,
                              String season,
                              String daysOfWeek,
                              LocalTime startTime,
                              LocalTime endTime) {
    }

    public record Winner(BigDecimal priceGross, String currency, AppliedRule rule) {
    }

    public record Candidate(BigDecimal priceGross, AppliedRule rule, String reasonLost) {
    }

    public record PriceCheck(Winner winner, List<Candidate> candidates) {
    }

    private record EntryKey(Long productId,
                            String uom,
                            String currency,
                            LocalTime startTime,
                            LocalTime endTime) {
        private static EntryKey from(PriceListEntry entry) {
            PriceProfile profile = entry.getPriceProfile();
            return new EntryKey(
                    entry.getProductId(),
                    normalize(entry.getUom()),
                    normalize(profile != null ? profile.getCurrency() : null),
                    entry.getStartTime(),
                    entry.getEndTime()
            );
        }

        private static String normalize(String value) {
            return value == null ? null : value.trim().toUpperCase();
        }
    }
}
