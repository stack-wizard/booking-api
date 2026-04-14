package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ManagementForecastDailyTrendPoint;
import com.stackwizard.booking_api.dto.ManagementForecastDailyTrendResponse;
import com.stackwizard.booking_api.dto.ManagementForecastProductRow;
import com.stackwizard.booking_api.dto.ManagementForecastResponse;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.ManagementForecastRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class ManagementForecastService {

    private static final int MAX_DAILY_TREND_DAYS = 62;
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d/M");

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private final ManagementForecastRepository forecastRepository;

    public ManagementForecastService(ManagementForecastRepository forecastRepository) {
        this.forecastRepository = forecastRepository;
    }

    @Transactional(readOnly = true)
    public ManagementForecastResponse getForecast(Long tenantId, OffsetDateTime from, OffsetDateTime to) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must be on or after from");
        }

        long requestCount = forecastRepository.countFinalizedRequests(
                tenantId,
                ReservationRequest.Status.FINALIZED,
                ReservationRequest.Type.INTERNAL,
                from,
                to);

        long reservationCount = forecastRepository.countForecastReservations(
                tenantId,
                ReservationRequest.Status.FINALIZED,
                ReservationRequest.Type.INTERNAL,
                from,
                to);

        BigDecimal grossTotal = toBigDecimal(forecastRepository.sumForecastGross(
                tenantId,
                ReservationRequest.Status.FINALIZED,
                ReservationRequest.Type.INTERNAL,
                from,
                to));

        List<Object[]> rows = forecastRepository.aggregateReservationsByProduct(
                tenantId,
                ReservationRequest.Status.FINALIZED,
                ReservationRequest.Type.INTERNAL,
                from,
                to);

        List<ManagementForecastProductRow> byProduct = new ArrayList<>();
        for (Object[] row : rows) {
            Long productId = row[0] != null ? ((Number) row[0]).longValue() : null;
            String productName = row[1] != null ? row[1].toString() : "";
            long resCount = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            BigDecimal grossSum = toBigDecimal(row[3]);
            long personCount = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            byProduct.add(ManagementForecastProductRow.builder()
                    .productId(productId)
                    .productName(productName)
                    .reservationCount(resCount)
                    .personCount(personCount)
                    .grossSum(grossSum)
                    .build());
        }

        return ManagementForecastResponse.builder()
                .from(from)
                .to(to)
                .finalizedRequestCount(requestCount)
                .reservationCount(reservationCount)
                .grossTotal(grossTotal)
                .byProduct(byProduct)
                .build();
    }

    /**
     * One response for a multi-day chart: per calendar day in {@code from}/{@code to}'s offset,
     * same filters as {@link #getForecast(Long, OffsetDateTime, OffsetDateTime)} (bookings + gross only).
     */
    @Transactional(readOnly = true)
    public ManagementForecastDailyTrendResponse getDailyTrend(Long tenantId, OffsetDateTime from, OffsetDateTime to) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must be on or after from");
        }
        ZoneOffset offset = from.getOffset();
        if (!offset.equals(to.getOffset())) {
            throw new IllegalArgumentException("from and to must use the same UTC offset");
        }
        LocalDate start = from.toLocalDate();
        LocalDate end = to.toLocalDate();
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_DAILY_TREND_DAYS) {
            throw new IllegalArgumentException("range must be at most " + MAX_DAILY_TREND_DAYS + " days");
        }

        List<ManagementForecastDailyTrendPoint> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            OffsetDateTime dayStart = OffsetDateTime.of(d, LocalTime.MIN, offset);
            OffsetDateTime dayEnd = OffsetDateTime.of(d, LocalTime.of(23, 59, 59, 999_000_000), offset);

            long reservationCount = forecastRepository.countForecastReservations(
                    tenantId,
                    ReservationRequest.Status.FINALIZED,
                    ReservationRequest.Type.INTERNAL,
                    dayStart,
                    dayEnd);
            BigDecimal grossTotal = toBigDecimal(forecastRepository.sumForecastGross(
                    tenantId,
                    ReservationRequest.Status.FINALIZED,
                    ReservationRequest.Type.INTERNAL,
                    dayStart,
                    dayEnd));

            days.add(ManagementForecastDailyTrendPoint.builder()
                    .day(d)
                    .label(LABEL.format(d))
                    .reservationCount(reservationCount)
                    .grossTotal(grossTotal)
                    .build());
        }

        return ManagementForecastDailyTrendResponse.builder()
                .days(days)
                .build();
    }
}
