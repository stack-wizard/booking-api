package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.SeasonDtos;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.PriceProfile;
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
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeasonServiceTest {
    @Mock SeasonCalendarRepository calendarRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock SeasonOperatingRepository operatingRepo;
    @Mock SalesCatalogRepository catalogRepo;
    @Mock PriceProfileDateRepository periodRepo;
    @Mock PriceListEntryRepository priceRepo;
    @Mock LocationNodeRepository locationRepo;
    @Mock PricingAccess access;

    SeasonService service;
    SeasonCalendar calendar = SeasonCalendar.builder().id(1L).tenantId(2L).name("Beach").build();
    Season pre = season(10L, "PRE_2027", "Pre season", "2027-05-01", "2027-06-14");
    Season high = season(11L, "HIGH_2027", "High season", "2027-06-15", "2027-09-06");

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(2L);
        service = new SeasonService(calendarRepo, seasonRepo, operatingRepo, catalogRepo, periodRepo, priceRepo, locationRepo,
                TenantHierarchyTestSupport.standalone(), access);
        when(calendarRepo.findByIdAndTenantId(1L, 2L)).thenReturn(Optional.of(calendar));
        when(seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(1L)).thenReturn(List.of(pre, high));
        when(seasonRepo.saveAndFlush(any())).thenAnswer(inv -> {
            Season s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(99L);
            }
            return s;
        });
        when(periodRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    static Season season(Long id, String code, String name, String from, String to) {
        return Season.builder().id(id).tenantId(2L).seasonCalendarId(1L).code(code).name(name)
                .dateFrom(LocalDate.parse(from)).dateTo(LocalDate.parse(to)).status(Season.Status.OPEN).displayOrder(0).build();
    }

    @Test
    void overlappingSeasonIsConflictNamingTheOtherSeason() {
        assertThatThrownBy(() -> service.createSeason(1L, new SeasonDtos.SeasonInput("PEAK", "Peak",
                LocalDate.parse("2027-06-10"), LocalDate.parse("2027-06-20"), null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pre season")
                .hasMessageContaining("PRE_2027");
        verify(seasonRepo, never()).saveAndFlush(any());
    }

    @Test
    void duplicateCodeIsConflict() {
        assertThatThrownBy(() -> service.createSeason(1L, new SeasonDtos.SeasonInput("high_2027", "Again",
                LocalDate.parse("2027-10-01"), LocalDate.parse("2027-10-31"), null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HIGH_2027 already exists");
    }

    @Test
    void invalidInputIsBadRequestWithFieldName() {
        assertThatThrownBy(() -> service.createSeason(1L, new SeasonDtos.SeasonInput("POST", " ",
                LocalDate.parse("2027-10-01"), LocalDate.parse("2027-10-31"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("name is required");
        assertThatThrownBy(() -> service.createSeason(1L, new SeasonDtos.SeasonInput("POST", "Post",
                LocalDate.parse("2027-10-31"), LocalDate.parse("2027-10-01"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dateTo");
        assertThatThrownBy(() -> service.createSeason(1L, new SeasonDtos.SeasonInput("PO ST", "Post",
                LocalDate.parse("2027-10-01"), LocalDate.parse("2027-10-31"), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("code");
    }

    @Test
    void createsAdjacentSeasonOpenByDefault() {
        SeasonDtos.SeasonRow row = service.createSeason(1L, new SeasonDtos.SeasonInput("post_2027", "Post",
                LocalDate.parse("2027-09-07"), LocalDate.parse("2027-10-15"), null, null, null));
        assertThat(row.code()).isEqualTo("POST_2027");
        assertThat(row.status()).isEqualTo(Season.Status.OPEN);
    }

    @Test
    void writesNeedPricingRole() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "no")).when(access).requireWrite(any());
        assertThatThrownBy(() -> service.deleteSeason(10L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deleteCalendarUsedByCatalogIsConflict() {
        when(catalogRepo.findBySeasonCalendarId(1L)).thenReturn(List.of(SalesCatalog.builder().code("BEACH").build()));
        assertThatThrownBy(() -> service.deleteCalendar(1L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BEACH");
    }

    @Test
    void deleteSeasonUnlinksPricePeriods() {
        when(seasonRepo.findByIdAndTenantId(10L, 2L)).thenReturn(Optional.of(pre));
        service.deleteSeason(10L);
        verify(periodRepo).unlinkSeason(10L);
        verify(seasonRepo).delete(pre);
    }

    @Test
    void operatingRejectsDuplicateLocationAndBadTimes() {
        when(seasonRepo.findByIdAndTenantId(10L, 2L)).thenReturn(Optional.of(pre));
        SeasonDtos.Operating all = new SeasonDtos.Operating(null, LocalTime.of(9, 0), LocalTime.of(19, 0), 60, 60, 600);
        assertThatThrownBy(() -> service.replaceOperating(10L, List.of(all, all)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("operating[1]");
        assertThatThrownBy(() -> service.replaceOperating(10L, List.of(
                new SeasonDtos.Operating(null, LocalTime.of(19, 0), LocalTime.of(9, 0), 60, 0, 0))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("closeTime must be after openTime");
    }

    @Test
    void copyYearShiftsToSameWeekdayAndCopiesHoursPeriodsAndPrices() {
        PriceProfile profile = PriceProfile.builder().id(6L).currency("EUR").build();
        PriceProfileDate base = PriceProfileDate.builder().id(30L).priceProfile(profile).seasonId(11L)
                .dateFrom(high.getDateFrom()).dateTo(high.getDateTo()).periodKind(PriceProfileDate.PeriodKind.SEASON)
                .description("HIGH 2027").build();
        when(periodRepo.findBySeasonIds(List.of(11L))).thenReturn(List.of(base));
        when(periodRepo.findBySeasonIds(List.of(10L))).thenReturn(List.of());
        when(operatingRepo.findBySeasonIdOrderByLocationNodeIdAscIdAsc(11L)).thenReturn(List.of(SeasonOperating.builder()
                .tenantId(2L).seasonId(11L).openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(20, 0)).gridMinutes(60)
                .minDurationMinutes(0).maxDurationMinutes(0).build()));
        when(priceRepo.findByPeriodIds(any())).thenReturn(List.of(PriceListEntry.builder().productId(46L).uom("DAY")
                .price(new BigDecimal("30.00")).daysOfWeek((short) 96).priceProfile(profile).priceProfileDate(base).build()));

        SeasonDtos.CopyYearResult result = service.copyYear(1L, new SeasonDtos.CopyYearRequest(2027, 2028, true, true,
                new BigDecimal("10"), new BigDecimal("1"), false));

        assertThat(result.seasons()).isEqualTo(2);
        assertThat(result.operating()).isEqualTo(1);
        assertThat(result.pricePeriods()).isEqualTo(1);
        assertThat(result.prices()).isEqualTo(1);
        assertThat(result.planned()).extracting(SeasonDtos.PlannedSeason::code).containsExactly("PRE_2028", "HIGH_2028");
        SeasonDtos.PlannedSeason copiedHigh = result.planned().get(1);
        assertThat(copiedHigh.dateFrom().getDayOfWeek()).isEqualTo(high.getDateFrom().getDayOfWeek());
        assertThat(copiedHigh.dateFrom()).isEqualTo(LocalDate.parse("2028-06-13"));
        assertThat(copiedHigh.dateTo()).isEqualTo(LocalDate.parse("2028-09-04"));

        ArgumentCaptor<PriceListEntry> price = ArgumentCaptor.forClass(PriceListEntry.class);
        verify(priceRepo).save(price.capture());
        assertThat(price.getValue().getPrice()).isEqualByComparingTo("33.00");
        assertThat(price.getValue().getDaysOfWeek()).isEqualTo((short) 96);
        assertThat(price.getValue().getPriceProfileDate().getSeasonId()).isEqualTo(99L);
        assertThat(price.getValue().getPriceProfileDate().getDescription()).isEqualTo("HIGH 2028");
        verify(operatingRepo).save(any(SeasonOperating.class));
    }

    @Test
    void copyYearSameDateWithoutWeekdayShift() {
        SeasonDtos.CopyYearResult result = service.copyYear(1L, new SeasonDtos.CopyYearRequest(2027, 2028, false, false,
                null, null, true));
        assertThat(result.planned().get(1).dateFrom()).isEqualTo(LocalDate.parse("2028-06-15"));
        assertThat(result.planned().get(1).dateFrom().getDayOfWeek()).isNotEqualTo(DayOfWeek.TUESDAY);
    }

    @Test
    void copyYearDryRunSavesNothingAndReportsSkips() {
        Season existing2028 = season(20L, "HIGH_2028", "High 2028", "2028-06-13", "2028-09-04");
        when(seasonRepo.findBySeasonCalendarIdOrderByDateFromAscIdAsc(1L)).thenReturn(List.of(pre, high, existing2028));
        when(periodRepo.findBySeasonIds(any())).thenReturn(List.of());

        SeasonDtos.CopyYearResult result = service.copyYear(1L, new SeasonDtos.CopyYearRequest(2027, 2028, null, null, null, null, true));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.seasons()).isEqualTo(1);
        assertThat(result.skipped()).singleElement().asString().contains("HIGH_2028 already exists");
        verify(seasonRepo, never()).saveAndFlush(any());
        verify(operatingRepo, never()).save(any());
    }

    @Test
    void copyYearValidatesYears() {
        assertThatThrownBy(() -> service.copyYear(1L, new SeasonDtos.CopyYearRequest(2027, 2027, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.copyYear(1L, new SeasonDtos.CopyYearRequest(2026, 2027, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no season starting in 2026");
    }

    @Test
    void retagReplacesYearsInCodesAndNames() {
        assertThat(SeasonService.retag("HIGH_2027", 2027, 2028, true)).isEqualTo("HIGH_2028");
        assertThat(SeasonService.retag("HIGH_27", 2027, 2028, true)).isEqualTo("HIGH_28");
        assertThat(SeasonService.retag("HIGH", 2027, 2028, true)).isEqualTo("HIGH_2028");
        assertThat(SeasonService.retag("High", 2027, 2028, false)).isEqualTo("High 2028");
    }

    @Test
    void unknownCalendarIs404() {
        when(calendarRepo.findByIdAndTenantId(anyLong(), anyLong())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.seasons(5L, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }
}
