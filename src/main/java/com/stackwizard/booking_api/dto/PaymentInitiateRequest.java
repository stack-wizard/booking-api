package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class PaymentInitiateRequest {
    private String provider;
    private String successUrl;
    private String cancelUrl;
    private String callbackUrl;
    private String language;
    private String orderInfo;
    private String transactionType;
}
