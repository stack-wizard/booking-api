package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagementForecastProductRow {
    private Long productId;
    private String productName;
    private long reservationCount;
    /** Sum of {@code requestedResource.capTotal} × effective line qty (qty null/0 → 1). */
    private long personCount;
    private BigDecimal grossSum;
}
