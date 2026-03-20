package com.stackwizard.booking_api.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentTransactionCreateRequest {
    private Long tenantId;
    private Long reservationRequestId;
    private Long paymentIntentId;
    private String paymentType;
    private String cardType;
    private String status;
    private String currency;
    private BigDecimal amount;
    private String externalRef;
    private String note;
}
