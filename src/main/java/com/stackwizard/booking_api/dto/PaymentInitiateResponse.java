package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
public class PaymentInitiateResponse {
    private Long paymentIntentId;
    private String provider;
    private String status;
    private String orderNumber;
    private String providerPaymentId;
    private String clientSecret;
    private BigDecimal amount;
    private String currency;
    private OffsetDateTime expiresAt;
}
