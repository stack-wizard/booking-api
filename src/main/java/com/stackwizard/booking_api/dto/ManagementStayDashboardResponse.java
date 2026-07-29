package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagementStayDashboardResponse {
    private OffsetDateTime from;
    private OffsetDateTime to;
    /**
     * Distinct non-INTERNAL reservation requests in {@code CHECKED_IN} or {@code PARTIALLY_CHECKED_IN}
     * with at least one reservation line in {@code CHECKED_IN} whose stay window overlaps the range.
     */
    private long checkedInRequestCount;
    /**
     * Same as {@link #checkedInRequestCount} for {@code CHECKED_OUT}.
     */
    private long checkedOutRequestCount;
    private long checkedInReservationCount;
    private long checkedOutReservationCount;
    /** Sum of {@code invoice_item.gross_amount} for issued, non-deposit invoices in date range. */
    private BigDecimal invoiceLineGrossTotal;
    private List<ManagementStayDashboardProductRow> byProduct;
    private List<ManagementStayDashboardCountryRow> byCountry;
}
