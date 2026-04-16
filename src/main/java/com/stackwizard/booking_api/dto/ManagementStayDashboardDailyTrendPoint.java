package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagementStayDashboardDailyTrendPoint {
    private LocalDate day;
    private String label;
    private long checkedInReservationCount;
    private BigDecimal invoiceLineGrossTotal;
}
