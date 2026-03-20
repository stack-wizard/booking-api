package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransactionDto {
    private Long id;
    private Long tenantId;
    private Long reservationRequestId;
    private Long paymentIntentId;
    private String paymentType;
    private String cardType;
    private String status;
    private String currency;
    private BigDecimal amount;
    private BigDecimal allocatedAmount;
    private BigDecimal availableAmount;
    private String externalRef;
    private String note;
    private OffsetDateTime createdAt;
}
