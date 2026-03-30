package com.stackwizard.booking_api.service.payment;

import com.stackwizard.booking_api.dto.PaymentInitiateRequest;
import com.stackwizard.booking_api.model.PaymentIntent;

public interface PaymentProviderClient {
    String providerCode();

    PaymentProviderInitResult initiate(PaymentIntent paymentIntent, PaymentInitiateRequest request);

    PaymentProviderRefundResult refund(PaymentProviderRefundRequest request);

    PaymentProviderWebhookResult parseWebhook(String payload);
}
