package com.stackwizard.booking_api.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class InvoicePaymentAllocationRequest {
    private Long paymentTransactionId;
    private BigDecimal amount;
}
