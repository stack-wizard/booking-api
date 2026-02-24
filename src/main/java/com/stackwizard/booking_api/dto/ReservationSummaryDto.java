package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Data
@Builder
public class ReservationSummaryDto {
    private Long id;
    private Long tenantId;
    private Long productId;
    private String productName;
    private Long requestId;
    private String requestType;
    private Long requestedResourceId;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private String status;
    private OffsetDateTime expiresAt;
    private Integer adults;
    private Integer children;
    private Integer infants;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String currency;
    private Integer qty;
    private BigDecimal unitPrice;
    private BigDecimal grossAmount;
}
