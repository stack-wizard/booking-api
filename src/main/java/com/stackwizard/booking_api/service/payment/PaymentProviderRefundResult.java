package com.stackwizard.booking_api.service.payment;

public record PaymentProviderRefundResult(
        String providerRefundId,
        String externalRef,
        String providerStatus
) {
}
