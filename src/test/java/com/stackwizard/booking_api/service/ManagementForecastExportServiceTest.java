package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ManagementForecastProductRow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementForecastExportServiceTest {

    @Mock
    private ManagementForecastService forecastService;

    @Test
    void exportRevenueByProductCreatesWorkbookWithFilteredRows() throws Exception {
        ManagementForecastExportService service = new ManagementForecastExportService(forecastService);
        Long tenantId = 1L;
        OffsetDateTime from = OffsetDateTime.parse("2026-05-01T00:00:00+02:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-05-31T23:59:59+02:00");

        when(forecastService.getRevenueByProduct(tenantId, from, to))
                .thenReturn(List.of(
                        ManagementForecastProductRow.builder()
                                .productId(11L)
                                .productName("Cabana")
                                .reservationCount(2)
                                .personCount(3)
                                .grossSum(new BigDecimal("150.00"))
                                .build(),
                        ManagementForecastProductRow.builder()
                                .productId(22L)
                                .productName("Sunbed")
                                .reservationCount(5)
                                .personCount(5)
                                .grossSum(new BigDecimal("100.00"))
                                .build()
                ));

        byte[] bytes = service.exportRevenueByProduct(tenantId, from, to);

        assertThat(bytes).isNotEmpty();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Revenue by Product");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("From");
            assertThat(sheet.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(11d);
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("Cabana");
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(2d);
            assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(3d);
            assertThat(sheet.getRow(1).getCell(6).getNumericCellValue()).isEqualTo(150d);
        }
    }
}
