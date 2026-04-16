package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ManagementStayDashboardDailyTrendPoint;
import com.stackwizard.booking_api.dto.ManagementStayDashboardDailyTrendResponse;
import com.stackwizard.booking_api.dto.ManagementStayDashboardProductRow;
import com.stackwizard.booking_api.dto.ManagementStayDashboardResponse;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.ManagementStayDashboardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class ManagementStayDashboardService {

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

    private final ManagementStayDashboardRepository stayDashboardRepository;

    public ManagementStayDashboardService(ManagementStayDashboardRepository stayDashboardRepository) {
        this.stayDashboardRepository = stayDashboardRepository;
    }

    @Transactional(readOnly = true)
    public ManagementStayDashboardResponse getStayDashboard(Long tenantId, OffsetDateTime from, OffsetDateTime to) {
        validateRange(tenantId, from, to, false);
        ZoneOffset offset = from.getOffset();
        LocalDateTime rangeStart = from.toLocalDateTime();
        LocalDateTime rangeEnd = to.withOffsetSameInstant(offset).toLocalDateTime();
        LocalDate fromDate = from.toLocalDate();
        LocalDate toDate = to.withOffsetSameInstant(offset).toLocalDate();

        long checkedInRequests = stayDashboardRepository.countDistinctRequestsOverlappingStay(
                tenantId,
                ReservationRequest.Type.INTERNAL,
                ReservationRequest.Status.CHECKED_IN,
                rangeStart,
                rangeEnd);
        long checkedOutRequests = stayDashboardRepository.countDistinctRequestsOverlappingStay(
                tenantId,
                ReservationRequest.Type.INTERNAL,
                ReservationRequest.Status.CHECKED_OUT,
                rangeStart,
                rangeEnd);
        long checkedInReservations = stayDashboardRepository.countReservationsOverlappingStay(
                tenantId,
                ReservationRequest.Type.INTERNAL,
                ReservationRequest.Status.CHECKED_IN,
                rangeStart,
                rangeEnd);
        long checkedOutReservations = stayDashboardRepository.countReservationsOverlappingStay(
                tenantId,
                ReservationRequest.Type.INTERNAL,
                ReservationRequest.Status.CHECKED_OUT,
                rangeStart,
                rangeEnd);

        BigDecimal invoiceGross = toBigDecimal(
                stayDashboardRepository.sumInvoiceLineGross(tenantId, fromDate, toDate));

        List<Object[]> rows = stayDashboardRepository.aggregateInvoiceLinesByProduct(tenantId, fromDate, toDate);
        List<ManagementStayDashboardProductRow> byProduct = new ArrayList<>();
        for (Object[] row : rows) {
            Long productId = row[0] != null ? ((Number) row[0]).longValue() : null;
            String productName = row[1] != null ? row[1].toString() : "";
            long lineCount = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            BigDecimal grossSum = toBigDecimal(row[3]);
            long qtySum = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            byProduct.add(ManagementStayDashboardProductRow.builder()
                    .productId(productId)
                    .productName(productName)
                    .invoiceLineCount(lineCount)
                    .quantitySum(qtySum)
                    .grossSum(grossSum)
                    .build());
        }

        return ManagementStayDashboardResponse.builder()
                .from(from)
                .to(to)
                .checkedInRequestCount(checkedInRequests)
                .checkedOutRequestCount(checkedOutRequests)
                .checkedInReservationCount(checkedInReservations)
                .checkedOutReservationCount(checkedOutReservations)
                .invoiceLineGrossTotal(invoiceGross)
                .byProduct(byProduct)
                .build();
    }

    @Transactional(readOnly = true)
    public ManagementStayDashboardDailyTrendResponse getDailyTrend(Long tenantId, OffsetDateTime from, OffsetDateTime to) {
        validateRange(tenantId, from, to, true);
        ZoneOffset offset = from.getOffset();
        LocalDate start = from.toLocalDate();
        LocalDate end = to.withOffsetSameInstant(offset).toLocalDate();

        List<ManagementStayDashboardDailyTrendPoint> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            LocalDateTime dayStart = LocalDateTime.of(d, LocalTime.MIN);
            LocalDateTime dayEnd = LocalDateTime.of(d, LocalTime.of(23, 59, 59, 999_000_000));

            long checkedInReservations = stayDashboardRepository.countReservationsOverlappingStay(
                    tenantId,
                    ReservationRequest.Type.INTERNAL,
                    ReservationRequest.Status.CHECKED_IN,
                    dayStart,
                    dayEnd);
            BigDecimal dayGross = toBigDecimal(stayDashboardRepository.sumInvoiceLineGross(tenantId, d, d));

            days.add(ManagementStayDashboardDailyTrendPoint.builder()
                    .day(d)
                    .label(LABEL.format(d))
                    .checkedInReservationCount(checkedInReservations)
                    .invoiceLineGrossTotal(dayGross)
                    .build());
        }

        return ManagementStayDashboardDailyTrendResponse.builder()
                .days(days)
                .build();
    }

    private static void validateRange(Long tenantId, OffsetDateTime from, OffsetDateTime to, boolean enforceMaxDays) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must be on or after from");
        }
        if (!from.getOffset().equals(to.getOffset())) {
            throw new IllegalArgumentException("from and to must use the same UTC offset");
        }
        if (enforceMaxDays) {
            LocalDate start = from.toLocalDate();
            LocalDate end = to.withOffsetSameInstant(from.getOffset()).toLocalDate();
            long span = ChronoUnit.DAYS.between(start, end) + 1;
            if (span > MAX_DAILY_TREND_DAYS) {
                throw new IllegalArgumentException("range must be at most " + MAX_DAILY_TREND_DAYS + " days");
            }
        }
    }
}
