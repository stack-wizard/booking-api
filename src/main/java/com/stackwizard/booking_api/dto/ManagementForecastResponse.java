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
public class ManagementForecastResponse {
    private OffsetDateTime from;
    private OffsetDateTime to;
    private long finalizedRequestCount;
    private long reservationCount;
    private BigDecimal grossTotal;
    private List<ManagementForecastProductRow> byProduct;
}
