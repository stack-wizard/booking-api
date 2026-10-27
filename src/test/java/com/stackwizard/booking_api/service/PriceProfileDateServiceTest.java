package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.Season;
import com.stackwizard.booking_api.repository.PriceProfileDateRepository;
import com.stackwizard.booking_api.repository.SeasonRepository;
import com.stackwizard.booking_api.support.DaysOfWeek;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PriceProfileDateServiceTest {
    @Mock PriceProfileDateRepository repo;
    @Mock SeasonRepository seasonRepo;

    @Test
    void subPeriodAndDatesMustLieInsideTheirSeason() {
        PriceProfileDateService service = new PriceProfileDateService(repo, seasonRepo);
        when(seasonRepo.findById(5L)).thenReturn(Optional.of(Season.builder().id(5L).name("Summer")
                .dateFrom(LocalDate.of(2027, 6, 1)).dateTo(LocalDate.of(2027, 9, 30)).build()));

        PriceProfileDate legacy = PriceProfileDate.builder().dateFrom(LocalDate.of(2027, 1, 1)).dateTo(LocalDate.of(2027, 12, 31))
                .periodKind(null).build();
        service.validate(legacy);
        assertThat(legacy.getPeriodKind()).isEqualTo(PriceProfileDate.PeriodKind.SEASON);

        assertThatThrownBy(() -> service.validate(PriceProfileDate.builder().dateFrom(LocalDate.of(2027, 7, 15))
                .dateTo(LocalDate.of(2027, 8, 15)).periodKind(PriceProfileDate.PeriodKind.SUB_PERIOD).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("seasonId is required");
        assertThatThrownBy(() -> service.validate(PriceProfileDate.builder().seasonId(5L).dateFrom(LocalDate.of(2027, 9, 15))
                .dateTo(LocalDate.of(2027, 10, 15)).periodKind(PriceProfileDate.PeriodKind.DATES).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inside season 'Summer'");
        service.validate(PriceProfileDate.builder().seasonId(5L).dateFrom(LocalDate.of(2027, 8, 15))
                .dateTo(LocalDate.of(2027, 8, 15)).periodKind(PriceProfileDate.PeriodKind.DATES).build());
    }

    @Test
    void weekdayMask() {
        assertThat(DaysOfWeek.toMask(List.of("fri", "SAT"))).isEqualTo((short) 48);
        assertThat(DaysOfWeek.toMask(List.of())).isNull();
        assertThat(DaysOfWeek.toMask(List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"))).isNull();
        assertThat(DaysOfWeek.toList((short) 65)).containsExactly("MON", "SUN");
        assertThat(DaysOfWeek.matches((short) 48, LocalDate.of(2027, 7, 24))).isTrue();
        assertThat(DaysOfWeek.matches((short) 48, LocalDate.of(2027, 7, 20))).isFalse();
        assertThatThrownBy(() -> DaysOfWeek.toMask(List.of("XYZ"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown day 'XYZ'");
    }
}
