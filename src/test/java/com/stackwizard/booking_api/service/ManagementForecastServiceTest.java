package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ManagementForecastCountryRow;
import com.stackwizard.booking_api.dto.ManagementForecastProductRow;
import com.stackwizard.booking_api.dto.ManagementForecastResponse;
import com.stackwizard.booking_api.model.Country;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.CountryRepository;
import com.stackwizard.booking_api.repository.ManagementForecastRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementForecastServiceTest {

    @Mock
    private ManagementForecastRepository forecastRepository;
    @Mock
    private CountryRepository countryRepository;

    @Test
    void getForecastUsesFinalizedOnlyRulesAndBuildsRows() {
        ManagementForecastService service = new ManagementForecastService(forecastRepository, countryRepository);
        Long tenantId = 1L;
        OffsetDateTime from = OffsetDateTime.parse("2026-05-01T00:00:00+02:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-05-31T23:59:59+02:00");

        when(forecastRepository.countFinalizedRequests(eq(tenantId), anyCollection(), eq(ReservationRequest.Type.INTERNAL), eq(from), eq(to)))
                .thenReturn(4L);
        when(forecastRepository.countForecastReservations(eq(tenantId), anyCollection(), eq(ReservationRequest.Type.INTERNAL), eq(from), eq(to)))
                .thenReturn(7L);
        when(forecastRepository.sumForecastGross(eq(tenantId), anyCollection(), eq(ReservationRequest.Type.INTERNAL), eq(from), eq(to)))
                .thenReturn(new BigDecimal("250.00"));
        when(forecastRepository.aggregateReservationsByProduct(eq(tenantId), anyCollection(), eq(ReservationRequest.Type.INTERNAL), eq(from), eq(to)))
                .thenReturn(List.of(
                        new Object[]{11L, "Cabana", 2L, new BigDecimal("150.00"), 3L},
                        new Object[]{22L, "Sunbed", 5L, new BigDecimal("100.00"), 5L}
                ));
        when(forecastRepository.aggregateReservationsByCountry(eq(tenantId), anyCollection(), eq(ReservationRequest.Type.INTERNAL), eq(from), eq(to)))
                .thenReturn(List.of(
                        new Object[]{"HR", 2L, 1L, 3L, new BigDecimal("150.00"), 3L},
                        new Object[]{null, 5L, 3L, 5L, new BigDecimal("100.00"), 5L}
                ));
        when(countryRepository.findAllById(anyIterable()))
                .thenReturn(List.of(Country.builder().code("HR").name("Croatia").build()));

        ManagementForecastResponse response = service.getForecast(tenantId, from, to);

        assertThat(response.getFinalizedRequestCount()).isEqualTo(4L);
        assertThat(response.getReservationCount()).isEqualTo(7L);
        assertThat(response.getGrossTotal()).isEqualByComparingTo("250.00");
        assertThat(response.getByProduct())
                .extracting(ManagementForecastProductRow::getProductName, ManagementForecastProductRow::getGrossSum)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Cabana", new BigDecimal("150.00")),
                        org.assertj.core.groups.Tuple.tuple("Sunbed", new BigDecimal("100.00"))
                );
        assertThat(response.getByCountry())
                .extracting(ManagementForecastCountryRow::getCountryName, ManagementForecastCountryRow::getGrossSum)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Croatia", new BigDecimal("150.00")),
                        org.assertj.core.groups.Tuple.tuple("Unknown", new BigDecimal("100.00"))
                );

        verify(forecastRepository).countFinalizedRequests(
                eq(tenantId),
                eq(List.of(ReservationRequest.Status.FINALIZED)),
                eq(ReservationRequest.Type.INTERNAL),
                eq(from),
                eq(to)
        );
    }

    @Test
    void getRevenueByProductReturnsRoundedRowsForSelectedRange() {
        ManagementForecastService service = new ManagementForecastService(forecastRepository, countryRepository);
        Long tenantId = 1L;
        OffsetDateTime from = OffsetDateTime.parse("2026-05-01T00:00:00+02:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-05-31T23:59:59+02:00");

        when(forecastRepository.aggregateReservationsByProduct(eq(tenantId), anyCollection(), eq(ReservationRequest.Type.INTERNAL), eq(from), eq(to)))
                .thenReturn(Collections.singletonList(
                        new Object[]{11L, "Cabana", 2L, new BigDecimal("150.126"), 3L}
                ));

        List<ManagementForecastProductRow> rows = service.getRevenueByProduct(tenantId, from, to);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getProductName()).isEqualTo("Cabana");
        assertThat(rows.getFirst().getGrossSum()).isEqualByComparingTo("150.13");
    }
}
