package com.stackwizard.booking_api.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class InvoiceCreateItemRequest {
    private Long productId;
    private String productName;
    private String description;
    private Integer quantity;
    private String uom;
    private BigDecimal unitPriceGross;
    private BigDecimal grossAmount;
    private BigDecimal discountPercent;
    private BigDecimal tax1Percent;
    private BigDecimal tax2Percent;
}
