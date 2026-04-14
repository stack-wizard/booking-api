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
public class ManagementForecastDailyTrendPoint {
    /** Calendar day in the same offset as the request range (typically the client browser offset). */
    private LocalDate day;
    /** Short label for charts, e.g. 9/4 */
    private String label;
    private long reservationCount;
    private BigDecimal grossTotal;
}
