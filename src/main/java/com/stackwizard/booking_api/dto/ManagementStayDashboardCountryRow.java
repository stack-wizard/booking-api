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
public class ManagementStayDashboardCountryRow {
    /** ISO 3166-1 alpha-2 when known; null when guest country was not captured. */
    private String countryCode;
    /** Resolved from {@link com.stackwizard.booking_api.model.Country} or {@code Unknown}. */
    private String countryName;
    /** Number of invoice line rows (same basis as {@link ManagementStayDashboardProductRow#getInvoiceLineCount()}). */
    private long invoiceLineCount;
    /** Sum of line quantities (same basis as {@link ManagementStayDashboardProductRow#getQuantitySum()}). */
    private long quantitySum;
    /** Sum of line gross amounts (same basis as {@link ManagementStayDashboardProductRow#getGrossSum()}). */
    private BigDecimal grossSum;
}
