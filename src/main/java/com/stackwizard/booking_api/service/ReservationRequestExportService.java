package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.dto.ReservationRequestSearchCriteria;
import com.stackwizard.booking_api.dto.ReservationSummaryDto;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.ReservationRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ReservationRequestExportService {
    /** Entity / DTO batches per DB page (avoids loading the full result set at once). */
    private static final int EXPORT_PAGE_SIZE = 100;
    /** SXSSF: only this many recent sheet rows stay in heap; older rows are flushed to temp storage. */
    private static final int SXSSF_ROW_ACCESS_WINDOW = 200;

    private static final String SUMMARY_SHEET_NAME = "Summary";
    private static final List<String> SUMMARY_HEADERS = List.of(
            "Reservation start",
            "Reservation request ID",
            "Guest name",
            "Email",
            "Resource code (map)",
            "Resource name (map)",
            "Notes"
    );

    private static final String SHEET_NAME = "Reservation Requests";
    private static final List<String> HEADERS = List.of(
            "Request ID",
            "Tenant ID",
            "Request Type",
            "Request Status",
            "Created At",
            "Expires At",
            "Confirmed At",
            "Confirmation Code",
            "Customer Name",
            "Customer Email",
            "Customer Phone",
            "Request Cancellation Policy",
            "Notes",
            "External Reservation",
            "Public Access URL",
            "Public Access Expires At",
            "QR Payload",
            "Extension Count",
            "Payment Total Amount",
            "Payment Due Now Amount",
            "Payment Paid Amount",
            "Payment Remaining Amount",
            "Payment Status",
            "Request Reservation Starts At",
            "Request Reservation Ends At",
            "Reservation ID",
            "Reservation Tenant ID",
            "Reservation Product Name",
            "Reservation Request ID",
            "Reservation Request Type",
            "Requested Resource Code",
            "Requested Resource Name",
            "Reservation Starts At",
            "Reservation Ends At",
            "Reservation Status",
            "Reservation Expires At",
            "Reservation Adults",
            "Reservation Children",
            "Reservation Infants",
            "Reservation Customer Name",
            "Reservation Customer Email",
            "Reservation Customer Phone",
            "Reservation Currency",
            "Reservation Qty",
            "Reservation Unit Price",
            "Reservation Gross Amount",
            "Reservation Cancellation Policy"
    );

    private final ReservationRequestService reservationRequestService;
    private final ReservationRequestDtoMapper dtoMapper;
    private final ReservationRepository reservationRepository;

    public ReservationRequestExportService(ReservationRequestService reservationRequestService,
                                           ReservationRequestDtoMapper dtoMapper,
                                           ReservationRepository reservationRepository) {
        this.reservationRequestService = reservationRequestService;
        this.dtoMapper = dtoMapper;
        this.reservationRepository = reservationRepository;
    }

    public byte[] exportSearch(ReservationRequestSearchCriteria criteria, Sort sort) {
        Sort effectiveSort = (sort == null || sort.isUnsorted()) ? Sort.by(Sort.Direction.DESC, "createdAt") : sort;

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(SXSSF_ROW_ACCESS_WINDOW);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle decimalStyle = createDecimalStyle(workbook);
            CellStyle dateTimeStyle = createDateTimeStyle(workbook);

            int rowIndex = 0;
            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < HEADERS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS.get(i));
                cell.setCellStyle(headerStyle);
            }

            int pageNumber = 0;
            Page<ReservationRequest> page;
            do {
                Pageable pageable = PageRequest.of(pageNumber, EXPORT_PAGE_SIZE, effectiveSort);
                page = reservationRequestService.search(criteria, pageable);
                List<ReservationRequest> chunk = page.getContent();
                if (chunk.isEmpty()) {
                    break;
                }
                for (ReservationRequest entity : chunk) {
                    ReservationRequestDto request = dtoMapper.toDto(entity);
                    List<ReservationSummaryDto> reservations = request.getReservations();
                    if (reservations == null || reservations.isEmpty()) {
                        Row row = sheet.createRow(rowIndex++);
                        writeRow(row, request, null, decimalStyle, dateTimeStyle);
                        continue;
                    }
                    for (ReservationSummaryDto reservation : reservations) {
                        Row row = sheet.createRow(rowIndex++);
                        writeRow(row, request, reservation, decimalStyle, dateTimeStyle);
                    }
                }
                pageNumber++;
            } while (page.hasNext());

            sheet.createFreezePane(0, 1);
            for (int i = 0; i < HEADERS.size(); i++) {
                sheet.setColumnWidth(i, 18 * 256);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate reservation request export", ex);
        }
    }

    /**
     * Lightweight export: one row per reservation line (or one row per request without lines),
     * same {@link ReservationRequestSearchCriteria} as {@link #exportSearch}. Uses paged scans and
     * batched {@code IN} reservation queries instead of building full {@link ReservationRequestDto}s.
     */
    public byte[] exportSearchSummary(ReservationRequestSearchCriteria criteria, Sort sort) {
        Sort effectiveSort = (sort == null || sort.isUnsorted()) ? Sort.by(Sort.Direction.DESC, "createdAt") : sort;

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(SXSSF_ROW_ACCESS_WINDOW);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet(SUMMARY_SHEET_NAME);
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle dateTimeStyle = createDateTimeStyle(workbook);

            int rowIndex = 0;
            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < SUMMARY_HEADERS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(SUMMARY_HEADERS.get(i));
                cell.setCellStyle(headerStyle);
            }

            int pageNumber = 0;
            Page<ReservationRequest> page;
            do {
                Pageable pageable = PageRequest.of(pageNumber, EXPORT_PAGE_SIZE, effectiveSort);
                page = reservationRequestService.search(criteria, pageable);
                List<ReservationRequest> chunk = page.getContent();
                if (chunk.isEmpty()) {
                    break;
                }
                List<Long> requestIds = chunk.stream().map(ReservationRequest::getId).filter(Objects::nonNull).toList();
                Map<Long, List<Reservation>> reservationsByRequestId = loadReservationsGrouped(requestIds);

                for (ReservationRequest request : chunk) {
                    List<Reservation> lines = reservationsByRequestId.getOrDefault(request.getId(), List.of());
                    if (lines.isEmpty()) {
                        Row row = sheet.createRow(rowIndex++);
                        writeSummaryRow(row, request, null, dateTimeStyle);
                        continue;
                    }
                    lines.sort(Comparator.comparing(Reservation::getId, Comparator.nullsLast(Long::compareTo)));
                    for (Reservation reservation : lines) {
                        Row row = sheet.createRow(rowIndex++);
                        writeSummaryRow(row, request, reservation, dateTimeStyle);
                    }
                }
                pageNumber++;
            } while (page.hasNext());

            sheet.createFreezePane(0, 1);
            sheet.setColumnWidth(0, 22 * 256);
            sheet.setColumnWidth(1, 14 * 256);
            sheet.setColumnWidth(2, 28 * 256);
            sheet.setColumnWidth(3, 30 * 256);
            sheet.setColumnWidth(4, 18 * 256);
            sheet.setColumnWidth(5, 28 * 256);
            sheet.setColumnWidth(6, 40 * 256);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate reservation request summary export", ex);
        }
    }

    private Map<Long, List<Reservation>> loadReservationsGrouped(List<Long> requestIds) {
        if (requestIds.isEmpty()) {
            return Map.of();
        }
        List<Reservation> batch = reservationRepository.findByRequestIdsWithDetails(requestIds);
        return batch.stream()
                .filter(r -> r.getStatus() == null || !"CANCELLED".equalsIgnoreCase(r.getStatus().trim()))
                .collect(Collectors.groupingBy(r -> r.getRequest().getId(), HashMap::new, Collectors.toList()));
    }

    private void writeSummaryRow(Row row, ReservationRequest request, Reservation reservation, CellStyle dateTimeStyle) {
        String guestName = firstNonBlank(
                reservation != null ? reservation.getCustomerName() : null,
                request.getCustomerName());
        String guestEmail = firstNonBlank(
                reservation != null ? reservation.getCustomerEmail() : null,
                request.getCustomerEmail());
        Resource resource = reservation != null ? reservation.getRequestedResource() : null;
        String resourceCode = resource != null ? resource.getCode() : null;
        String resourceName = resource != null ? resource.getName() : null;

        Object arrival = reservation != null ? reservation.getStartsAt() : null;

        writeSummaryCell(row, 0, arrival, dateTimeStyle);
        writeSummaryCell(row, 1, request.getId(), null);
        writeSummaryCell(row, 2, guestName, null);
        writeSummaryCell(row, 3, guestEmail, null);
        writeSummaryCell(row, 4, resourceCode, null);
        writeSummaryCell(row, 5, resourceName, null);
        writeSummaryCell(row, 6, request.getNotes(), null);
    }

    private void writeSummaryCell(Row row, int column, Object value, CellStyle dateTimeStyle) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            return;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            cell.setCellValue(java.util.Date.from(offsetDateTime.toInstant()));
            cell.setCellStyle(dateTimeStyle);
            return;
        }
        if (value instanceof LocalDateTime localDateTime) {
            cell.setCellValue(java.util.Date.from(localDateTime.toInstant(ZoneOffset.UTC)));
            cell.setCellStyle(dateTimeStyle);
            return;
        }
        cell.setCellValue(String.valueOf(value));
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary.trim();
        }
        if (StringUtils.hasText(fallback)) {
            return fallback.trim();
        }
        return null;
    }

    private void writeRow(Row row,
                          ReservationRequestDto request,
                          ReservationSummaryDto reservation,
                          CellStyle decimalStyle,
                          CellStyle dateTimeStyle) {
        List<Object> values = new ArrayList<>();
        values.add(request.getId());
        values.add(request.getTenantId());
        values.add(request.getType());
        values.add(request.getStatus());
        values.add(request.getCreatedAt());
        values.add(request.getExpiresAt());
        values.add(request.getConfirmedAt());
        values.add(request.getConfirmationCode());
        values.add(request.getCustomerName());
        values.add(request.getCustomerEmail());
        values.add(request.getCustomerPhone());
        values.add(request.getCancellationPolicyText());
        values.add(request.getNotes());
        values.add(request.getExternalReservation());
        values.add(request.getPublicAccessUrl());
        values.add(request.getPublicAccessExpiresAt());
        values.add(request.getQrPayload());
        values.add(request.getExtensionCount());
        values.add(request.getPaymentTotalAmount());
        values.add(request.getPaymentDueNowAmount());
        values.add(request.getPaymentPaidAmount());
        values.add(request.getPaymentRemainingAmount());
        values.add(request.getPaymentStatus());
        values.add(request.getReservationStartsAt());
        values.add(request.getReservationEndsAt());

        if (reservation == null) {
            for (int i = 0; i < 22; i++) {
                values.add(null);
            }
        } else {
            values.add(reservation.getId());
            values.add(reservation.getTenantId());
            values.add(reservation.getProductName());
            values.add(reservation.getRequestId());
            values.add(reservation.getRequestType());
            values.add(reservation.getRequestedResourceCode());
            values.add(reservation.getRequestedResourceName());
            values.add(reservation.getStartsAt());
            values.add(reservation.getEndsAt());
            values.add(reservation.getStatus());
            values.add(reservation.getExpiresAt());
            values.add(reservation.getAdults());
            values.add(reservation.getChildren());
            values.add(reservation.getInfants());
            values.add(reservation.getCustomerName());
            values.add(reservation.getCustomerEmail());
            values.add(reservation.getCustomerPhone());
            values.add(reservation.getCurrency());
            values.add(reservation.getQty());
            values.add(reservation.getUnitPrice());
            values.add(reservation.getGrossAmount());
            values.add(reservation.getCancellationPolicyText());
        }

        for (int i = 0; i < values.size(); i++) {
            Cell cell = row.createCell(i);
            writeCell(cell, values.get(i), decimalStyle, dateTimeStyle);
        }
    }

    private void writeCell(Cell cell, Object value, CellStyle decimalStyle, CellStyle dateTimeStyle) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            cell.setCellStyle(decimalStyle);
            return;
        }
        if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
            cell.setCellStyle(decimalStyle);
            return;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            cell.setCellValue(java.util.Date.from(offsetDateTime.toInstant()));
            cell.setCellStyle(dateTimeStyle);
            return;
        }
        if (value instanceof LocalDateTime localDateTime) {
            cell.setCellValue(java.util.Date.from(localDateTime.toInstant(ZoneOffset.UTC)));
            cell.setCellStyle(dateTimeStyle);
            return;
        }
        cell.setCellValue(String.valueOf(value));
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle createDecimalStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("0.00"));
        return style;
    }

    private CellStyle createDateTimeStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("yyyy-mm-dd hh:mm:ss"));
        return style;
    }
}
