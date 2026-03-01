package com.stackwizard.booking_api.service.payment;

public record PaymentProviderInitResult(
        String providerPaymentId,
        String clientSecret,
        String providerStatus
) {
}
