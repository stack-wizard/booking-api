package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest;
import com.stackwizard.booking_api.dto.PaymentTransactionDto;
import com.stackwizard.booking_api.dto.PaymentTransactionSearchCriteria;
import com.stackwizard.booking_api.model.PaymentEvent;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.PaymentEventRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.PaymentTransactionRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private ReservationRequestRepository reservationRequestRepo;
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
                reservationRequestRepo,
                paymentCardTypeService
        );
    }

    @Test
    void resolveFirstCardTypeForRequestUsesFirstPostedCardChargeWithCardType() {
        when(paymentTransactionRepo.findByReservationRequestIdOrderByCreatedAtAscIdAsc(10L)).thenReturn(List.of(
                PaymentTransaction.builder()
                        .transactionType("REFUND")
                        .paymentType("CARD")
                        .status("POSTED")
                        .cardType("MASTER")
                        .build(),
                PaymentTransaction.builder()
                        .transactionType("CHARGE")
                        .paymentType("CARD")
                        .status("POSTED")
                        .cardType("VISA")
                        .build(),
                PaymentTransaction.builder()
                        .transactionType("CHARGE")
                        .paymentType("CARD")
                        .status("POSTED")
                        .cardType("MASTER")
                        .build()
        ));
        when(paymentCardTypeService.findActiveDisplayNameOrCodeOrNull(1L, "VISA"))
                .thenReturn("Visa Premium");

        assertThat(service.resolveFirstCardTypeForRequest(10L, 1L)).isEqualTo("Visa Premium");
    }

    @Test
    void resolveCardTypeForIntentUsesLinkedPostedCardCharge() {
        when(paymentTransactionRepo.findFirstByPaymentIntentIdOrderByCreatedAtAscIdAsc(50L)).thenReturn(Optional.of(
                PaymentTransaction.builder()
                        .paymentIntentId(50L)
                        .transactionType("CHARGE")
                        .paymentType("CARD")
                        .status("POSTED")
                        .cardType("VISA")
                        .build()
        ));
        when(paymentCardTypeService.findActiveDisplayNameOrCodeOrNull(1L, "VISA"))
                .thenReturn("Visa Premium");

        assertThat(service.resolveCardTypeForIntent(50L, 1L)).isEqualTo("Visa Premium");
    }

    @Test
    void resolveCardTypeForIntentFallsBackToPaymentEventPayload() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("cc_type", "master");

        when(paymentTransactionRepo.findFirstByPaymentIntentIdOrderByCreatedAtAscIdAsc(51L))
                .thenReturn(Optional.empty());
        when(paymentEventRepo.findByPaymentIntentIdOrderByCreatedAtDesc(51L)).thenReturn(List.of(
                PaymentEvent.builder()
                        .paymentIntentId(51L)
                        .payload(payload)
                        .build()
        ));
        when(paymentCardTypeService.findActiveDisplayNameOrCodeOrNull(1L, "MASTER"))
                .thenReturn("Mastercard");

        assertThat(service.resolveCardTypeForIntent(51L, 1L)).isEqualTo("Mastercard");
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
        assertThat(transaction.getTransactionType()).isEqualTo("CHARGE");
    }

    @Test
    void ensureForPaidIntentStoresConfiguredCardTypeFromMonriCallbackCcType() {
        PaymentIntent paymentIntent = PaymentIntent.builder()
                .id(51L)
                .tenantId(1L)
                .reservationRequestId(11L)
                .provider("MONRI")
                .providerOrderNumber("ord-2")
                .idempotencyKey("idem-2")
                .currency("EUR")
                .amount(new BigDecimal("50.00"))
                .status("PAID")
                .build();
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("cc_type", "master");

        when(paymentTransactionRepo.findByPaymentIntentId(51L)).thenReturn(Optional.empty());
        when(paymentEventRepo.findByPaymentIntentIdOrderByCreatedAtDesc(51L)).thenReturn(List.of(
                PaymentEvent.builder()
                        .paymentIntentId(51L)
                        .provider("MONRI")
                        .eventType("callback:approved")
                        .payload(payload)
                        .build()
        ));
        when(paymentCardTypeService.findActiveCodeOrNull(1L, "MASTER")).thenReturn("MASTER");
        when(paymentTransactionRepo.save(any(PaymentTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentTransaction transaction = service.ensureForPaidIntent(paymentIntent);

        assertThat(transaction.getCardType()).isEqualTo("MASTER");
        assertThat(transaction.getTransactionType()).isEqualTo("CHARGE");
    }

    @Test
    void createManualRefundRequiresNegativeAmountAndChargeSource() {
        PaymentTransactionCreateRequest request = new PaymentTransactionCreateRequest();
        request.setTenantId(1L);
        request.setTransactionType("refund");
        request.setPaymentType("card");
        request.setStatus("posted");
        request.setCurrency("eur");
        request.setAmount(new BigDecimal("-50.00"));
        request.setRefundType("cancellation");
        request.setSourcePaymentTransactionId(40L);

        when(paymentTransactionRepo.findById(40L)).thenReturn(Optional.of(
                PaymentTransaction.builder()
                        .id(40L)
                        .tenantId(1L)
                        .transactionType("CHARGE")
                        .paymentType("CARD")
                        .status("POSTED")
                        .currency("EUR")
                        .amount(new BigDecimal("100.00"))
                        .build()
        ));
        when(paymentTransactionRepo.save(any(PaymentTransaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentTransactionDto transaction = service.create(request);

        assertThat(transaction.getTransactionType()).isEqualTo("REFUND");
        assertThat(transaction.getAmount()).isEqualTo(new BigDecimal("-50.00"));
        assertThat(transaction.getRefundType()).isEqualTo("CANCELLATION");
        assertThat(transaction.getSourcePaymentTransactionId()).isEqualTo(40L);
    }

    @Test
    void createManualRefundRejectsPositiveAmount() {
        PaymentTransactionCreateRequest request = new PaymentTransactionCreateRequest();
        request.setTenantId(1L);
        request.setTransactionType("refund");
        request.setPaymentType("card");
        request.setStatus("posted");
        request.setCurrency("eur");
        request.setAmount(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("less than zero");
    }

    @Test
    void searchOnlyWithAvailableAmountExcludesFullyAllocatedCharges() {
        PaymentTransaction fullyAllocated = PaymentTransaction.builder()
                .id(86L)
                .tenantId(1L)
                .reservationRequestId(514L)
                .transactionType("CHARGE")
                .paymentType("CARD")
                .status("POSTED")
                .currency("EUR")
                .amount(new BigDecimal("295.00"))
                .build();
        PaymentTransaction partiallyOpen = PaymentTransaction.builder()
                .id(87L)
                .tenantId(1L)
                .reservationRequestId(514L)
                .transactionType("CHARGE")
                .paymentType("CASH")
                .status("POSTED")
                .currency("EUR")
                .amount(new BigDecimal("100.00"))
                .build();

        when(paymentTransactionRepo.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(fullyAllocated, partiallyOpen));
        when(allocationRepo.sumAllocatedByPaymentTransactionIds(List.of(86L, 87L))).thenReturn(List.of(
                allocationSum(86L, new BigDecimal("295.00")),
                allocationSum(87L, new BigDecimal("40.00"))
        ));
        when(paymentTransactionRepo.findBySourcePaymentTransactionId(86L)).thenReturn(List.of());
        when(paymentTransactionRepo.findBySourcePaymentTransactionId(87L)).thenReturn(List.of());
        when(reservationRequestRepo.findAllById(List.of(514L))).thenReturn(List.of(
                com.stackwizard.booking_api.model.ReservationRequest.builder()
                        .id(514L)
                        .customerName("Ana Anić")
                        .confirmationCode("CONF-514")
                        .build()
        ));

        PaymentTransactionSearchCriteria criteria = new PaymentTransactionSearchCriteria();
        criteria.setTenantId(1L);
        criteria.setReservationRequestId(514L);
        criteria.setOnlyWithAvailableAmount(true);

        Page<PaymentTransactionDto> page = service.search(criteria, PageRequest.of(0, 100));

        assertThat(page.getContent()).extracting(PaymentTransactionDto::getId).containsExactly(87L);
        assertThat(page.getContent().get(0).getAvailableAmount()).isEqualByComparingTo("60.00");
        assertThat(page.getContent().get(0).getCustomerName()).isEqualTo("Ana Anić");
        assertThat(page.getContent().get(0).getConfirmationCode()).isEqualTo("CONF-514");
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    private static InvoicePaymentAllocationRepository.PaymentTransactionAllocationSum allocationSum(
            Long paymentTransactionId, BigDecimal totalAllocated) {
        return new InvoicePaymentAllocationRepository.PaymentTransactionAllocationSum() {
            @Override
            public Long getPaymentTransactionId() {
                return paymentTransactionId;
            }

            @Override
            public BigDecimal getTotalAllocated() {
                return totalAllocated;
            }
        };
    }
}
