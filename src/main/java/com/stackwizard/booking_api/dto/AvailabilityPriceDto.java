package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class AvailabilityPriceDto {
    private Long tenantId;
    private Long productId;
    private String uom;
    private BigDecimal amount;
    private String currency;
    private Long priceProfileId;
    private Long priceProfileDateId;
}
