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
public class ManagementStayDashboardProductRow {
    private Long productId;
    private String productName;
    /** Number of invoice line rows in the period (per product). */
    private long invoiceLineCount;
    /** Sum of {@code quantity} on those lines. */
    private long quantitySum;
    private BigDecimal grossSum;
}
