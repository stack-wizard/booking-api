package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.dto.ReservationRequestSearchCriteria;
import com.stackwizard.booking_api.dto.ReservationSummaryDto;
import com.stackwizard.booking_api.model.ReservationRequest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationRequestExportServiceTest {

    @Mock
    private ReservationRequestService reservationRequestService;
    @Mock
    private ReservationRequestDtoMapper dtoMapper;

    @Test
    void exportSearchFlattensReservationsIntoSeparateRows() throws Exception {
        ReservationRequestExportService service = new ReservationRequestExportService(reservationRequestService, dtoMapper);
        ReservationRequest request = ReservationRequest.builder()
                .id(10L)
                .tenantId(1L)
                .build();
        ReservationRequestDto dto = ReservationRequestDto.builder()
                .id(10L)
                .tenantId(1L)
                .type("EXTERNAL")
                .status("FINALIZED")
                .createdAt(OffsetDateTime.parse("2026-04-01T10:00:00Z"))
                .paymentTotalAmount(new BigDecimal("280.00"))
                .paymentDueNowAmount(new BigDecimal("140.00"))
                .paymentPaidAmount(new BigDecimal("140.00"))
                .paymentRemainingAmount(new BigDecimal("140.00"))
                .paymentStatus("PARTIALLY_PAID")
                .reservationStartsAt(LocalDateTime.of(2026, 4, 1, 9, 0))
                .reservationEndsAt(LocalDateTime.of(2026, 4, 1, 19, 0))
                .reservations(List.of(
                        ReservationSummaryDto.builder()
                                .id(100L)
                                .tenantId(1L)
                                .productName("Peninsula A")
                                .requestedResourceCode("PEN-A")
                                .requestedResourceName("Peninsula A Front")
                                .startsAt(LocalDateTime.of(2026, 4, 1, 9, 0))
                                .endsAt(LocalDateTime.of(2026, 4, 1, 14, 0))
                                .status("CONFIRMED")
                                .currency("EUR")
                                .qty(1)
                                .unitPrice(new BigDecimal("140.00"))
                                .grossAmount(new BigDecimal("140.00"))
                                .build(),
                        ReservationSummaryDto.builder()
                                .id(101L)
                                .tenantId(1L)
                                .productName("Peninsula B")
                                .requestedResourceCode("PEN-B")
                                .requestedResourceName("Peninsula B Front")
                                .startsAt(LocalDateTime.of(2026, 4, 1, 14, 0))
                                .endsAt(LocalDateTime.of(2026, 4, 1, 19, 0))
                                .status("CONFIRMED")
                                .currency("EUR")
                                .qty(1)
                                .unitPrice(new BigDecimal("140.00"))
                                .grossAmount(new BigDecimal("140.00"))
                                .build()
                ))
                .build();

        when(reservationRequestService.searchAll(org.mockito.ArgumentMatchers.any(ReservationRequestSearchCriteria.class), org.mockito.ArgumentMatchers.any(Sort.class)))
                .thenReturn(List.of(request));
        when(dtoMapper.toDto(request)).thenReturn(dto);

        byte[] bytes = service.exportSearch(new ReservationRequestSearchCriteria(), Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(bytes).isNotEmpty();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(10d);
            assertThat(sheet.getRow(1).getCell(25).getNumericCellValue()).isEqualTo(100d);
            assertThat(sheet.getRow(1).getCell(27).getStringCellValue()).isEqualTo("Peninsula A");
            assertThat(sheet.getRow(1).getCell(31).getStringCellValue()).isEqualTo("Peninsula A Front");
            assertThat(sheet.getRow(2).getCell(25).getNumericCellValue()).isEqualTo(101d);
            assertThat(sheet.getRow(2).getCell(27).getStringCellValue()).isEqualTo("Peninsula B");
            assertThat(sheet.getRow(2).getCell(31).getStringCellValue()).isEqualTo("Peninsula B Front");
        }
    }
}
