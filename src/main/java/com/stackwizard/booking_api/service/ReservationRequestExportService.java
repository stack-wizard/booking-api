package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.dto.ReservationRequestSearchCriteria;
import com.stackwizard.booking_api.dto.ReservationSummaryDto;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReservationRequestExportService {
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

    public ReservationRequestExportService(ReservationRequestService reservationRequestService,
                                           ReservationRequestDtoMapper dtoMapper) {
        this.reservationRequestService = reservationRequestService;
        this.dtoMapper = dtoMapper;
    }

    public byte[] exportSearch(ReservationRequestSearchCriteria criteria, Sort sort) {
        List<ReservationRequestDto> requests = reservationRequestService.searchAll(criteria, sort).stream()
                .map(dtoMapper::toDto)
                .toList();

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(SHEET_NAME);
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

            for (ReservationRequestDto request : requests) {
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
        XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
        XSSFFont font = (XSSFFont) workbook.createFont();
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
