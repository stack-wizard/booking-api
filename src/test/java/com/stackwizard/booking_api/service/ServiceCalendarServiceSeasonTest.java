package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.BookingCalendar;
import com.stackwizard.booking_api.model.LocationNode;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.model.SeasonCalendar;
import com.stackwizard.booking_api.model.SeasonOperating;
import com.stackwizard.booking_api.repository.BookingCalendarRepository;
import com.stackwizard.booking_api.repository.LocationNodeRepository;
import com.stackwizard.booking_api.repository.SeasonCalendarRepository;
import com.stackwizard.booking_api.repository.SeasonOperatingRepository;
import com.stackwizard.booking_api.repository.SeasonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceCalendarServiceSeasonTest {
    @Mock BookingCalendarRepository repo;
    @Mock LocationNodeRepository locationRepo;
    @Mock SeasonCalendarRepository seasonCalendarRepo;
    @Mock SeasonRepository seasonRepo;
    @Mock SeasonOperatingRepository operatingRepo;

    BookingCalendar beach = BookingCalendar.builder().tenantId(1L).locationNode(LocationNode.builder().id(8L).build())
            .openTime(LocalTime.of(9, 0)).closeTime(LocalTime.of(19, 0)).gridMinutes(60).build();

    ServiceCalendarService service() {
        return new ServiceCalendarService(repo, locationRepo, seasonCalendarRepo, seasonRepo, operatingRepo,
                TenantHierarchyTestSupport.standalone());
    }

    @Test
    void seasonHoursForLocationThenAllLocationsElseBookingCalendar() {
        LocalDate july = LocalDate.of(2027, 7, 1);
        when(seasonCalendarRepo.findApplicable(1L, 1L)).thenReturn(List.of(SeasonCalendar.builder().id(3L).build()));
        when(seasonRepo.findOnDateIn(any(), eq(july))).thenReturn(List.of(Season.builder().id(30L).seasonCalendarId(3L).build()));
        SeasonOperating all = SeasonOperating.builder().seasonId(30L).openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(20, 0)).build();
        SeasonOperating location = SeasonOperating.builder().seasonId(30L).locationNodeId(8L)
                .openTime(LocalTime.of(7, 0)).closeTime(LocalTime.of(21, 0)).build();
        when(operatingRepo.findBySeasonIdOrderByLocationNodeIdAscIdAsc(30L)).thenReturn(List.of(location, all));

        assertThat(service().windowFor(beach, july).open()).isEqualTo(july.atTime(7, 0));
        when(operatingRepo.findBySeasonIdOrderByLocationNodeIdAscIdAsc(30L)).thenReturn(List.of(all));
        assertThat(service().windowFor(beach, july).close()).isEqualTo(july.atTime(20, 0));

        LocalDate winter = LocalDate.of(2027, 1, 10);
        when(seasonRepo.findOnDateIn(any(), eq(winter))).thenReturn(List.of());
        assertThat(service().windowFor(beach, winter).open()).isEqualTo(winter.atTime(9, 0));
    }

    @Test
    void seasonGridAndDurationsOverrideBookingCalendar() {
        LocalDate july = LocalDate.of(2027, 7, 1);
        when(seasonCalendarRepo.findApplicable(1L, 1L)).thenReturn(List.of(SeasonCalendar.builder().id(3L).build()));
        when(seasonRepo.findOnDateIn(any(), eq(july))).thenReturn(List.of(Season.builder().id(30L).seasonCalendarId(3L).build()));
        when(operatingRepo.findBySeasonIdOrderByLocationNodeIdAscIdAsc(30L)).thenReturn(List.of(SeasonOperating.builder()
                .seasonId(30L).openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(20, 0))
                .gridMinutes(240).minDurationMinutes(480).maxDurationMinutes(720).build()));

        ServiceCalendarService.DayRules peak = service().rulesFor(beach, july);
        assertThat(peak.gridMinutes()).isEqualTo(240);
        assertThat(peak.minDurationMinutes()).isEqualTo(480);
        assertThat(peak.maxDurationMinutes()).isEqualTo(720);

        LocalDate winter = LocalDate.of(2027, 1, 10);
        when(seasonRepo.findOnDateIn(any(), eq(winter))).thenReturn(List.of());
        assertThat(service().rulesFor(beach, winter).gridMinutes()).isEqualTo(60);
    }

    @Test
    void withoutSeasonCalendarsBehavesAsBefore() {
        LocalDate d = LocalDate.of(2027, 7, 1);
        assertThat(new ServiceCalendarService(repo, locationRepo).windowFor(beach, d).close()).isEqualTo(d.atTime(19, 0));
        when(seasonCalendarRepo.findApplicable(1L, 1L)).thenReturn(List.of());
        assertThat(service().windowFor(beach, d).close()).isEqualTo(d.atTime(19, 0));
    }
}
