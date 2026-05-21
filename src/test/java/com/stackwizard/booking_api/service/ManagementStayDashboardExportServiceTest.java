package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ManagementStayDashboardProductRow;
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
class ManagementStayDashboardExportServiceTest {

    @Mock
    private ManagementStayDashboardService stayDashboardService;

    @Test
    void exportRevenueByProductCreatesWorkbookWithFilteredRows() throws Exception {
        ManagementStayDashboardExportService service = new ManagementStayDashboardExportService(stayDashboardService);
        Long tenantId = 1L;
        OffsetDateTime from = OffsetDateTime.parse("2026-05-01T00:00:00+02:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-05-31T23:59:59+02:00");

        when(stayDashboardService.getRevenueByProduct(tenantId, from, to))
                .thenReturn(List.of(
                        ManagementStayDashboardProductRow.builder()
                                .productId(11L)
                                .productName("Cabana")
                                .invoiceLineCount(2)
                                .quantitySum(3)
                                .grossSum(new BigDecimal("120.00"))
                                .build(),
                        ManagementStayDashboardProductRow.builder()
                                .productId(22L)
                                .productName("Sunbed")
                                .invoiceLineCount(1)
                                .quantitySum(1)
                                .grossSum(new BigDecimal("60.00"))
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
            assertThat(sheet.getRow(1).getCell(6).getNumericCellValue()).isEqualTo(120d);
        }
    }
}
