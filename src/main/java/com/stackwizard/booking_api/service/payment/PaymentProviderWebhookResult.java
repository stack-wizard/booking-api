package com.stackwizard.booking_api.service.payment;

public record PaymentProviderWebhookResult(
        String eventId,
        String eventType,
        String orderNumber,
        String providerPaymentId,
        String paymentStatus
) {
}
