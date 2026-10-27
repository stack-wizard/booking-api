package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.PriceProfile;
import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.SeasonRepository;
import com.stackwizard.booking_api.support.DaysOfWeek;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PriceListEntryResolverPrecedenceTest {
    private static final LocalDate SATURDAY = LocalDate.of(2027, 7, 24);
    private static final LocalDate TUESDAY = LocalDate.of(2027, 7, 20);

    @Mock PriceListEntryRepository repo;
    @Mock SeasonRepository seasonRepo;

    PriceListEntryResolver resolver;
    long ids = 1;

    @BeforeEach
    void setUp() {
        resolver = new PriceListEntryResolver(repo, TenantHierarchyTestSupport.standalone(), seasonRepo);
        when(seasonRepo.findByIdIn(any())).thenReturn(List.of(
                Season.builder().id(5L).code("SUMMER_2027").build()));
    }

    private void candidates(PriceListEntry... entries) {
        when(repo.findCandidatesForProductUomOnDate(eq(15L), anyString(), anyString(), anyLong(), anyLong(), any(), any()))
                .thenReturn(List.of(entries));
    }

    private BigDecimal resolve(LocalDate date, ReservationRequest.Type type) {
        return resolver.resolve(15L, "DAY", "EUR", 3L, date, null, type).map(PriceListEntryResolver.ResolvedPrice::price).orElse(null);
    }

    private PriceListEntry price(String amount, Long property, ReservationRequest.Type type, PriceProfileDate.PeriodKind kind,
                                 LocalDate from, LocalDate to, Short days, OffsetDateTime created) {
        long id = ids++;
        return PriceListEntry.builder()
                .id(id)
                .productId(15L)
                .uom("DAY")
                .price(new BigDecimal(amount))
                .daysOfWeek(days)
                .createdAt(created)
                .priceProfile(PriceProfile.builder().id(100 + id).name("P" + id).tenantId(3L).currency("EUR")
                        .propertyTenantId(property).reservationRequestType(type).build())
                .priceProfileDate(PriceProfileDate.builder().id(200 + id).dateFrom(from).dateTo(to).periodKind(kind)
                        .seasonId(5L).build())
                .build();
    }

    private PriceListEntry season(String amount) {
        return price(amount, null, null, PriceProfileDate.PeriodKind.SEASON, LocalDate.of(2027, 6, 1), LocalDate.of(2027, 9, 30), null, null);
    }

    @Test
    void hotelLevelBeatsEverythingBelowIt() {
        PriceListEntry chainDates = price("50", null, ReservationRequest.Type.EXTERNAL, PriceProfileDate.PeriodKind.DATES,
                SATURDAY, SATURDAY, DaysOfWeek.toMask(List.of("SAT")), null);
        PriceListEntry hotelSeason = price("30", 3L, null, PriceProfileDate.PeriodKind.SEASON,
                LocalDate.of(2027, 6, 1), LocalDate.of(2027, 9, 30), null, null);
        candidates(chainDates, hotelSeason);
        assertThat(resolve(SATURDAY, ReservationRequest.Type.EXTERNAL)).isEqualByComparingTo("30");
    }

    @Test
    void channelBeatsPeriodKindAndWeekday() {
        PriceListEntry genericDates = price("50", null, null, PriceProfileDate.PeriodKind.DATES, SATURDAY, SATURDAY,
                DaysOfWeek.toMask(List.of("SAT")), null);
        PriceListEntry externalSeason = price("30", null, ReservationRequest.Type.EXTERNAL, PriceProfileDate.PeriodKind.SEASON,
                LocalDate.of(2027, 6, 1), LocalDate.of(2027, 9, 30), null, null);
        candidates(genericDates, externalSeason);
        assertThat(resolve(SATURDAY, ReservationRequest.Type.EXTERNAL)).isEqualByComparingTo("30");
    }

    @Test
    void datesBeforeSubPeriodBeforeSeason() {
        PriceListEntry base = season("30");
        PriceListEntry peak = price("40", null, null, PriceProfileDate.PeriodKind.SUB_PERIOD,
                LocalDate.of(2027, 7, 15), LocalDate.of(2027, 8, 15), null, null);
        PriceListEntry holiday = price("50", null, null, PriceProfileDate.PeriodKind.DATES,
                LocalDate.of(2027, 8, 15), LocalDate.of(2027, 8, 15), null, null);
        candidates(holiday, base, peak);
        assertThat(resolve(LocalDate.of(2027, 8, 15), null)).isEqualByComparingTo("50");
        candidates(base, peak);
        assertThat(resolve(LocalDate.of(2027, 8, 14), null)).isEqualByComparingTo("40");
    }

    /** The spec example: weekend price of the base season loses to the peak sub-period; peak weekend price wins. */
    @Test
    void specExampleWeekendInsidePeak() {
        PriceListEntry base = season("30");
        PriceListEntry baseWeekend = price("35", null, null, PriceProfileDate.PeriodKind.SEASON,
                LocalDate.of(2027, 6, 1), LocalDate.of(2027, 9, 30), DaysOfWeek.toMask(List.of("SAT", "SUN")), null);
        PriceListEntry peak = price("40", null, null, PriceProfileDate.PeriodKind.SUB_PERIOD,
                LocalDate.of(2027, 7, 15), LocalDate.of(2027, 8, 15), null, null);
        candidates(base, baseWeekend, peak);
        assertThat(resolve(SATURDAY, null)).isEqualByComparingTo("40");
        assertThat(resolve(TUESDAY, null)).isEqualByComparingTo("40");

        PriceListEntry peakWeekend = price("45", null, null, PriceProfileDate.PeriodKind.SUB_PERIOD,
                LocalDate.of(2027, 7, 15), LocalDate.of(2027, 8, 15), DaysOfWeek.toMask(List.of("SAT", "SUN")), null);
        candidates(base, baseWeekend, peak, peakWeekend);
        assertThat(resolve(SATURDAY, null)).isEqualByComparingTo("45");
        assertThat(resolve(TUESDAY, null)).isEqualByComparingTo("40");
    }

    @Test
    void weekdayPriceWinsOnlyOnItsDays() {
        PriceListEntry base = season("30");
        PriceListEntry weekend = price("35", null, null, PriceProfileDate.PeriodKind.SEASON,
                LocalDate.of(2027, 6, 1), LocalDate.of(2027, 9, 30), DaysOfWeek.toMask(List.of("FRI", "SAT")), null);
        candidates(base, weekend);
        assertThat(resolve(SATURDAY, null)).isEqualByComparingTo("35");
        assertThat(resolve(TUESDAY, null)).isEqualByComparingTo("30");
    }

    @Test
    void tieBreakShorterPeriodThenNewer() {
        PriceListEntry longer = price("30", null, null, PriceProfileDate.PeriodKind.SEASON,
                LocalDate.of(2027, 6, 1), LocalDate.of(2027, 9, 30), null, OffsetDateTime.parse("2027-01-02T00:00Z"));
        PriceListEntry shorter = price("33", null, null, PriceProfileDate.PeriodKind.SEASON,
                LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 31), null, OffsetDateTime.parse("2027-01-01T00:00Z"));
        candidates(longer, shorter);
        assertThat(resolve(SATURDAY, null)).isEqualByComparingTo("33");

        PriceListEntry older = price("30", null, null, PriceProfileDate.PeriodKind.SEASON,
                LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 31), null, OffsetDateTime.parse("2027-01-01T00:00Z"));
        PriceListEntry newer = price("31", null, null, PriceProfileDate.PeriodKind.SEASON,
                LocalDate.of(2027, 7, 1), LocalDate.of(2027, 7, 31), null, OffsetDateTime.parse("2027-02-01T00:00Z"));
        candidates(older, newer);
        assertThat(resolve(SATURDAY, null)).isEqualByComparingTo("31");
        candidates(newer, older);
        assertThat(resolve(SATURDAY, null)).isEqualByComparingTo("31");
    }

    @Test
    void resolveReturnsAppliedRule() {
        PriceListEntry weekend = price("35", null, null, PriceProfileDate.PeriodKind.SUB_PERIOD,
                LocalDate.of(2027, 7, 15), LocalDate.of(2027, 8, 15), DaysOfWeek.toMask(List.of("FRI", "SAT")), null);
        candidates(weekend);
        PriceListEntryResolver.AppliedRule rule = resolver.resolve(15L, "DAY", "EUR", 3L, SATURDAY, null, null)
                .orElseThrow().rule();
        assertThat(rule.season()).isEqualTo("SUMMER_2027");
        assertThat(rule.periodKind()).isEqualTo(PriceProfileDate.PeriodKind.SUB_PERIOD);
        assertThat(rule.daysOfWeek()).isEqualTo("FRI,SAT");
        assertThat(rule.level()).isEqualTo("CHAIN");
    }

    @Test
    void explainListsWinnerAndWhyOthersLost() {
        PriceListEntry base = season("30");
        PriceListEntry weekend = price("35", null, null, PriceProfileDate.PeriodKind.SEASON,
                LocalDate.of(2027, 6, 1), LocalDate.of(2027, 9, 30), DaysOfWeek.toMask(List.of("SUN")), null);
        PriceListEntry peak = price("40", null, null, PriceProfileDate.PeriodKind.SUB_PERIOD,
                LocalDate.of(2027, 7, 15), LocalDate.of(2027, 8, 15), null, null);
        candidates(base, weekend, peak);
        PriceListEntryResolver.PriceCheck check = resolver.explain(15L, "DAY", "EUR", 3L, SATURDAY, null, null);
        assertThat(check.winner().priceGross()).isEqualByComparingTo("40");
        assertThat(check.candidates()).extracting(PriceListEntryResolver.Candidate::reasonLost)
                .containsExactlyInAnyOrder("SUB_PERIOD period wins over SEASON", "Not valid on SAT");
    }

    /**
     * Existing data (all SEASON, no weekdays, no overlapping periods on the same level and channel) must resolve exactly
     * like the old rule: specificity = level + channel, first candidate in query order wins ties.
     */
    @Test
    void legacyDataResolvesExactlyAsBefore() {
        Random random = new Random(42);
        ReservationRequest.Type[] types = {null, ReservationRequest.Type.EXTERNAL, ReservationRequest.Type.WALKIN};
        for (int round = 0; round < 500; round++) {
            List<PriceListEntry> list = new ArrayList<>();
            int n = 1 + random.nextInt(4);
            for (int i = 0; i < n; i++) {
                Long property = random.nextBoolean() ? 3L : null;
                ReservationRequest.Type type = types[random.nextInt(types.length)];
                // one period per level + channel, as in existing data
                boolean duplicate = list.stream().anyMatch(e -> java.util.Objects.equals(e.getPriceProfile().getPropertyTenantId(), property)
                        && e.getPriceProfile().getReservationRequestType() == type);
                if (duplicate) {
                    continue;
                }
                LocalDate from = SATURDAY.minusDays(random.nextInt(90));
                LocalDate to = SATURDAY.plusDays(random.nextInt(90));
                list.add(price(String.valueOf(10 + random.nextInt(90)), property, type, PriceProfileDate.PeriodKind.SEASON,
                        from, to, null, OffsetDateTime.parse("2026-01-01T00:00Z").plusDays(random.nextInt(100))));
            }
            ReservationRequest.Type requested = types[1 + random.nextInt(2)];
            // repository: generic profiles or the requested type only, ordered by level then channel then id
            List<PriceListEntry> ordered = list.stream()
                    .filter(e -> e.getPriceProfile().getReservationRequestType() == null
                            || e.getPriceProfile().getReservationRequestType() == requested)
                    .sorted(java.util.Comparator.<PriceListEntry>comparingInt(e -> e.getPriceProfile().getPropertyTenantId() != null ? 0 : 1)
                            .thenComparingInt(e -> e.getPriceProfile().getReservationRequestType() == requested ? 0 : 1)
                            .thenComparing(PriceListEntry::getId))
                    .toList();
            if (ordered.isEmpty()) {
                continue;
            }
            candidates(ordered.toArray(new PriceListEntry[0]));
            PriceListEntry legacy = null;
            int best = Integer.MAX_VALUE;
            for (PriceListEntry e : ordered) {
                int spec = (e.getPriceProfile().getPropertyTenantId() != null ? 0 : 2)
                        + (e.getPriceProfile().getReservationRequestType() == requested ? 0 : 1);
                if (spec < best) {
                    best = spec;
                    legacy = e;
                }
            }
            assertThat(resolve(SATURDAY, requested)).as("round " + round).isEqualByComparingTo(legacy.getPrice());
        }
    }
}
