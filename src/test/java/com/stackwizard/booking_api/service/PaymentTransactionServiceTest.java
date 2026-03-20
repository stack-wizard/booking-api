package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest;
import com.stackwizard.booking_api.model.PaymentEvent;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.PaymentEventRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionServiceTest {

    @Mock
    private PaymentTransactionRepository paymentTransactionRepo;
    @Mock
    private PaymentIntentRepository paymentIntentRepo;
    @Mock
    private PaymentEventRepository paymentEventRepo;
    @Mock
    private InvoicePaymentAllocationRepository allocationRepo;
    @Mock
    private PaymentCardTypeService paymentCardTypeService;

    private PaymentTransactionService service;

    @BeforeEach
    void setUp() {
        service = new PaymentTransactionService(
                paymentTransactionRepo,
                paymentIntentRepo,
                paymentEventRepo,
                allocationRepo,
                paymentCardTypeService
        );
    }

    @Test
    void ensureForPaidIntentStoresConfiguredCardTypeFromWebhookPayload() {
        PaymentIntent paymentIntent = PaymentIntent.builder()
                .id(50L)
                .tenantId(1L)
                .reservationRequestId(10L)
                .provider("MONRI")
                .providerOrderNumber("ord-1")
                .idempotencyKey("idem-1")
                .currency("EUR")
                .amount(new BigDecimal("170.00"))
                .status("PAID")
                .build();
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.putObject("payload")
                .putObject("payment_method")
                .putObject("card")
                .put("brand", "visa");

        when(paymentTransactionRepo.findByPaymentIntentId(50L)).thenReturn(Optional.empty());
        when(paymentEventRepo.findByPaymentIntentIdOrderByCreatedAtDesc(50L)).thenReturn(List.of(
                PaymentEvent.builder()
                        .paymentIntentId(50L)
                        .provider("MONRI")
                        .eventType("payment.succeeded")
                        .payload(payload)
                        .build()
        ));
        when(paymentCardTypeService.findActiveCodeOrNull(1L, "VISA")).thenReturn("VISA");
        when(paymentTransactionRepo.save(any(PaymentTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentTransaction transaction = service.ensureForPaidIntent(paymentIntent);

        assertThat(transaction.getCardType()).isEqualTo("VISA");
    }
}
