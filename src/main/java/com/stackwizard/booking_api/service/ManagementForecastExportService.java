package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ManagementForecastProductRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ManagementForecastExportService {
    private static final String SHEET_NAME = "Revenue by Product";
    private static final List<String> HEADERS = List.of(
            "From",
            "To",
            "Product ID",
            "Product Name",
            "Reservation Count",
            "Person Count",
            "Gross Revenue"
    );

    private final ManagementForecastService forecastService;

    public ManagementForecastExportService(ManagementForecastService forecastService) {
        this.forecastService = forecastService;
    }

    public byte[] exportRevenueByProduct(Long tenantId, OffsetDateTime from, OffsetDateTime to) {
        List<ManagementForecastProductRow> rows = forecastService.getRevenueByProduct(tenantId, from, to);

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.setCompressTempFiles(true);

            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle decimalStyle = createDecimalStyle(workbook);

            int rowIndex = 0;
            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < HEADERS.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS.get(i));
                cell.setCellStyle(headerStyle);
            }

            for (ManagementForecastProductRow row : rows) {
                Row sheetRow = sheet.createRow(rowIndex++);
                sheetRow.createCell(0).setCellValue(from.toString());
                sheetRow.createCell(1).setCellValue(to.toString());
                if (row.getProductId() != null) {
                    sheetRow.createCell(2).setCellValue(row.getProductId());
                }
                sheetRow.createCell(3).setCellValue(row.getProductName() != null ? row.getProductName() : "");
                sheetRow.createCell(4).setCellValue(row.getReservationCount());
                sheetRow.createCell(5).setCellValue(row.getPersonCount());
                Cell grossCell = sheetRow.createCell(6);
                if (row.getGrossSum() != null) {
                    grossCell.setCellValue(row.getGrossSum().doubleValue());
                }
                grossCell.setCellStyle(decimalStyle);
            }

            sheet.createFreezePane(0, 1);
            sheet.setColumnWidth(0, 24 * 256);
            sheet.setColumnWidth(1, 24 * 256);
            sheet.setColumnWidth(2, 14 * 256);
            sheet.setColumnWidth(3, 34 * 256);
            sheet.setColumnWidth(4, 18 * 256);
            sheet.setColumnWidth(5, 16 * 256);
            sheet.setColumnWidth(6, 18 * 256);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate forecast export", ex);
        }
    }

    private CellStyle createHeaderStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private CellStyle createDecimalStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00"));
        return style;
    }
}
