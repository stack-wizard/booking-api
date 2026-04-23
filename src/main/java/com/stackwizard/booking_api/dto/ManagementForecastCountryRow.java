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
public class ManagementForecastCountryRow {
    private String countryCode;
    private String countryName;
    /** Non-cancelled reservation rows in scope. */
    private long reservationCount;
    /** Distinct reservation requests with at least one such line. */
    private long reservationRequestCount;
    /** Sum of effective line quantities ({@code qty} null or 0 counts as 1). */
    private long quantitySum;
    /** Person-units from resource capacity (same basis as {@link ManagementForecastProductRow#getPersonCount()}). */
    private long personCount;
    /** Line gross (same basis as {@link ManagementForecastProductRow#getGrossSum()}). */
    private BigDecimal grossSum;
}
