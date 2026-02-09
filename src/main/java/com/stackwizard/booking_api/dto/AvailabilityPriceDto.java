package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

@Data
@Builder
public class AvailabilityPriceDto {
    private Long tenantId;
    private Long productId;
    private String productName;
    private String uom;
    private String uomName;
    private LocalTime startTime;
    private LocalTime endTime;
    private BigDecimal amount;
    private String currency;
    private Long priceProfileId;
    private Long priceProfileDateId;
}
