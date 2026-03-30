package com.stackwizard.booking_api.service.payment;

import java.math.BigDecimal;

public record PaymentProviderRefundRequest(
        Long tenantId,
        String providerPaymentId,
        String orderNumber,
        BigDecimal amount,
        String currency,
        String note
) {
}
