package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ManagementStayDashboardCountryRow;
import com.stackwizard.booking_api.dto.ManagementStayDashboardProductRow;
import com.stackwizard.booking_api.dto.ManagementStayDashboardResponse;
import com.stackwizard.booking_api.model.Country;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.CountryRepository;
import com.stackwizard.booking_api.repository.ManagementStayDashboardRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementStayDashboardServiceTest {

    @Mock
    private ManagementStayDashboardRepository stayDashboardRepository;
    @Mock
    private CountryRepository countryRepository;

    @Test
    void getStayDashboardBuildsRevenueRowsAndCountryBuckets() {
        ManagementStayDashboardService service = new ManagementStayDashboardService(
                stayDashboardRepository,
                countryRepository
        );
        Long tenantId = 1L;
        OffsetDateTime from = OffsetDateTime.parse("2026-05-01T00:00:00+02:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-05-31T23:59:59+02:00");

        when(stayDashboardRepository.countDistinctRequestsOverlappingStay(
                eq(tenantId), eq(ReservationRequest.Type.INTERNAL), eq(ReservationRequest.Status.CHECKED_IN), any(), any()))
                .thenReturn(3L);
        when(stayDashboardRepository.countDistinctRequestsOverlappingStay(
                eq(tenantId), eq(ReservationRequest.Type.INTERNAL), eq(ReservationRequest.Status.CHECKED_OUT), any(), any()))
                .thenReturn(2L);
        when(stayDashboardRepository.countReservationsOverlappingStay(
                eq(tenantId), eq(ReservationRequest.Type.INTERNAL), eq(ReservationRequest.Status.CHECKED_IN), any(), any()))
                .thenReturn(5L);
        when(stayDashboardRepository.countReservationsOverlappingStay(
                eq(tenantId), eq(ReservationRequest.Type.INTERNAL), eq(ReservationRequest.Status.CHECKED_OUT), any(), any()))
                .thenReturn(4L);
        when(stayDashboardRepository.sumInvoiceLineGross(eq(tenantId), any(), any()))
                .thenReturn(new BigDecimal("180.00"));
        when(stayDashboardRepository.aggregateInvoiceLinesByProduct(eq(tenantId), any(), any()))
                .thenReturn(List.of(
                        new Object[]{11L, "Cabana", 2L, new BigDecimal("120.00"), 3L},
                        new Object[]{22L, "Sunbed", 1L, new BigDecimal("60.00"), 1L}
                ));
        when(stayDashboardRepository.aggregateInvoiceLinesByCountry(eq(tenantId), any(), any()))
                .thenReturn(List.of(
                        new Object[]{"HR", 2L, new BigDecimal("120.00"), 3L},
                        new Object[]{null, 1L, new BigDecimal("60.00"), 1L}
                ));
        when(countryRepository.findAllById(anyIterable()))
                .thenReturn(List.of(Country.builder().code("HR").name("Croatia").build()));

        ManagementStayDashboardResponse response = service.getStayDashboard(tenantId, from, to);

        assertThat(response.getCheckedInRequestCount()).isEqualTo(3L);
        assertThat(response.getCheckedOutRequestCount()).isEqualTo(2L);
        assertThat(response.getCheckedInReservationCount()).isEqualTo(5L);
        assertThat(response.getCheckedOutReservationCount()).isEqualTo(4L);
        assertThat(response.getInvoiceLineGrossTotal()).isEqualByComparingTo("180.00");

        assertThat(response.getByProduct())
                .extracting(ManagementStayDashboardProductRow::getProductName, ManagementStayDashboardProductRow::getGrossSum)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Cabana", new BigDecimal("120.00")),
                        org.assertj.core.groups.Tuple.tuple("Sunbed", new BigDecimal("60.00"))
                );

        assertThat(response.getByCountry())
                .extracting(ManagementStayDashboardCountryRow::getCountryName, ManagementStayDashboardCountryRow::getGrossSum)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Croatia", new BigDecimal("120.00")),
                        org.assertj.core.groups.Tuple.tuple("Unknown", new BigDecimal("60.00"))
                );
    }

    @Test
    void getRevenueByProductReturnsRoundedRowsForSelectedRange() {
        ManagementStayDashboardService service = new ManagementStayDashboardService(
                stayDashboardRepository,
                countryRepository
        );
        Long tenantId = 1L;
        OffsetDateTime from = OffsetDateTime.parse("2026-05-01T00:00:00+02:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-05-31T23:59:59+02:00");

        when(stayDashboardRepository.aggregateInvoiceLinesByProduct(eq(tenantId), any(), any()))
                .thenReturn(java.util.Collections.singletonList(
                        new Object[]{11L, "Cabana", 2L, new BigDecimal("120.126"), 3L}
                ));

        List<ManagementStayDashboardProductRow> rows = service.getRevenueByProduct(tenantId, from, to);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getProductName()).isEqualTo("Cabana");
        assertThat(rows.getFirst().getGrossSum()).isEqualByComparingTo("120.13");
    }
}
