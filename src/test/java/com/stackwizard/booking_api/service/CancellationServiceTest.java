package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CancellationExecuteRequest;
import com.stackwizard.booking_api.dto.CancellationRequestDto;
import com.stackwizard.booking_api.dto.PaymentTransactionDto;
import com.stackwizard.booking_api.dto.PublicCancellationPreviewDto;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.CancellationRequest;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceFiscalizationStatus;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.CancellationRequestRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.fiscal.InvoiceAutoFiscalizationRequestedEvent;
import com.stackwizard.booking_api.service.payment.PaymentProviderClient;
import com.stackwizard.booking_api.service.payment.PaymentProviderRefundRequest;
import com.stackwizard.booking_api.service.payment.PaymentProviderRefundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancellationServiceTest {

    @Mock
    private CancellationRequestRepository cancellationRequestRepo;
    @Mock
    private ReservationRequestRepository requestRepo;
    @Mock
    private ReservationRepository reservationRepo;
    @Mock
    private AllocationRepository allocationRepo;
    @Mock
    private PaymentIntentRepository paymentIntentRepo;
    @Mock
    private InvoiceService invoiceService;
    @Mock
    private PaymentTransactionService paymentTransactionService;
    @Mock
    private CancellationPolicyService cancellationPolicyService;
    @Mock
    private ReservationRequestAccessTokenService accessTokenService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private PaymentProviderClient paymentProviderClient;

    private CancellationService service;

    @BeforeEach
    void setUp() {
        when(paymentProviderClient.providerCode()).thenReturn("MONRI");
        service = new CancellationService(
                cancellationRequestRepo,
                requestRepo,
                reservationRepo,
                allocationRepo,
                paymentIntentRepo,
                invoiceService,
                paymentTransactionService,
                cancellationPolicyService,
                accessTokenService,
                eventPublisher,
                List.of(paymentProviderClient)
        );
    }

    @Test
    void executeCashRefundCreatesCreditNotePenaltyAndRefundTransaction() {
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(10L)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .build();
        Reservation reservation = Reservation.builder()
                .id(20L)
                .tenantId(1L)
                .startsAt(LocalDateTime.now().plusDays(20))
                .status("CONFIRMED")
                .currency("EUR")
                .grossAmount(new BigDecimal("100.00"))
                .build();
        Allocation allocation = Allocation.builder()
                .id(30L)
                .status("CONFIRMED")
                .reservation(reservation)
                .build();
        Invoice sourceInvoice = Invoice.builder()
                .id(200L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .currency("EUR")
                .totalGross(new BigDecimal("100.00"))
                .build();
        PaymentTransaction sourceCharge = PaymentTransaction.builder()
                .id(300L)
                .tenantId(1L)
                .paymentIntentId(400L)
                .transactionType("CHARGE")
                .paymentType("CARD")
                .cardType("VISA")
                .status("POSTED")
                .currency("EUR")
                .amount(new BigDecimal("100.00"))
                .build();
        InvoicePaymentAllocation sourceAllocation = InvoicePaymentAllocation.builder()
                .id(500L)
                .invoice(sourceInvoice)
                .paymentTransaction(sourceCharge)
                .allocatedAmount(new BigDecimal("100.00"))
                .allocationType("SETTLEMENT")
                .build();
        Invoice creditNote = Invoice.builder()
                .id(201L)
                .tenantId(1L)
                .invoiceType(InvoiceType.CREDIT_NOTE)
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .totalGross(new BigDecimal("-100.00"))
                .build();
        PaymentIntent paymentIntent = PaymentIntent.builder()
                .id(400L)
                .tenantId(1L)
                .provider("MONRI")
                .providerPaymentId("monri-payment-1")
                .providerOrderNumber("order-1")
                .build();
        PaymentTransaction refundTransaction = PaymentTransaction.builder()
                .id(301L)
                .tenantId(1L)
                .transactionType("REFUND")
                .amount(new BigDecimal("-50.00"))
                .build();

        when(requestRepo.findById(10L)).thenReturn(Optional.of(reservationRequest));
        when(cancellationRequestRepo.findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(10L)).thenReturn(Optional.empty());
        when(reservationRepo.findByRequestId(10L)).thenReturn(List.of(reservation));
        when(invoiceService.findCancellationSourceInvoiceByRequestId(10L)).thenReturn(Optional.of(sourceInvoice));
        when(invoiceService.findAllocations(200L)).thenReturn(List.of(sourceAllocation));
        when(cancellationPolicyService.evaluateReservation(reservation, null))
                .thenReturn(new CancellationPolicyService.ReservationCancellationEvaluation(
                        new BigDecimal("100.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("50.00"),
                        "CASH_REFUND",
                        reservation.getStartsAt().minusDays(14),
                        501L,
                        "BEFORE_CUTOFF"
                ));
        when(cancellationRequestRepo.save(any(CancellationRequest.class))).thenAnswer(invocation -> {
            CancellationRequest saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(900L);
            }
            return saved;
        });
        when(paymentIntentRepo.findById(400L)).thenReturn(Optional.of(paymentIntent));
        when(paymentProviderClient.refund(any(PaymentProviderRefundRequest.class)))
                .thenReturn(new PaymentProviderRefundResult("refund-1", "refund-1", "approved"));
        when(invoiceService.createCreditNoteInvoice(200L)).thenReturn(creditNote);
        when(paymentTransactionService.create(any())).thenReturn(PaymentTransactionDto.builder().id(301L).build());
        when(paymentTransactionService.requireById(301L)).thenReturn(refundTransaction);
        when(allocationRepo.findByReservationIdIn(List.of(20L))).thenReturn(List.of(allocation));

        CancellationExecuteRequest executeRequest = new CancellationExecuteRequest();
        executeRequest.setSettlementMode("CASH_REFUND");
        executeRequest.setNote("Customer cancelled");

        CancellationRequestDto result = service.execute(10L, executeRequest);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getCreditNoteInvoiceId()).isEqualTo(201L);
        assertThat(result.getPenaltyInvoiceId()).isNull();
        assertThat(result.getRefundPaymentTransactionId()).isEqualTo(301L);
        assertThat(result.getRefundAmount()).isEqualByComparingTo("50.00");
        assertThat(reservationRequest.getStatus()).isEqualTo(ReservationRequest.Status.CANCELLED);
        assertThat(reservation.getStatus()).isEqualTo("CANCELLED");
        assertThat(allocation.getStatus()).isEqualTo("CANCELLED");

        ArgumentCaptor<com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest> paymentCaptor =
                ArgumentCaptor.forClass(com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest.class);
        verify(paymentTransactionService).create(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("-50.00");
        assertThat(paymentCaptor.getValue().getCreditNoteInvoiceId()).isEqualTo(201L);
        assertThat(paymentCaptor.getValue().getSourcePaymentTransactionId()).isEqualTo(300L);
        verify(invoiceService, never()).createPenaltyInvoice(10L, new BigDecimal("50.00"), "EUR");
        verify(eventPublisher, times(1)).publishEvent(any(InvoiceAutoFiscalizationRequestedEvent.class));
    }

    @Test
    void executeCashRefundWithoutPaymentIntentSkipsProviderAndCreatesManualRefundTransaction() {
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(16L)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .build();
        Reservation reservation = Reservation.builder()
                .id(26L)
                .tenantId(1L)
                .startsAt(LocalDateTime.now().plusDays(20))
                .status("CONFIRMED")
                .currency("EUR")
                .grossAmount(new BigDecimal("100.00"))
                .build();
        Allocation allocation = Allocation.builder()
                .id(36L)
                .status("CONFIRMED")
                .reservation(reservation)
                .build();
        Invoice sourceInvoice = Invoice.builder()
                .id(260L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .currency("EUR")
                .totalGross(new BigDecimal("100.00"))
                .build();
        PaymentTransaction sourceCharge = PaymentTransaction.builder()
                .id(360L)
                .tenantId(1L)
                .paymentIntentId(null)
                .transactionType("CHARGE")
                .paymentType("CARD")
                .cardType("VISA")
                .status("POSTED")
                .currency("EUR")
                .amount(new BigDecimal("100.00"))
                .build();
        InvoicePaymentAllocation sourceAllocation = InvoicePaymentAllocation.builder()
                .id(560L)
                .invoice(sourceInvoice)
                .paymentTransaction(sourceCharge)
                .allocatedAmount(new BigDecimal("100.00"))
                .allocationType("SETTLEMENT")
                .build();
        Invoice creditNote = Invoice.builder()
                .id(261L)
                .tenantId(1L)
                .invoiceType(InvoiceType.CREDIT_NOTE)
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .totalGross(new BigDecimal("-100.00"))
                .build();
        PaymentTransaction refundTransaction = PaymentTransaction.builder()
                .id(361L)
                .tenantId(1L)
                .transactionType("REFUND")
                .amount(new BigDecimal("-50.00"))
                .build();

        when(requestRepo.findById(16L)).thenReturn(Optional.of(reservationRequest));
        when(cancellationRequestRepo.findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(16L)).thenReturn(Optional.empty());
        when(reservationRepo.findByRequestId(16L)).thenReturn(List.of(reservation));
        when(invoiceService.findCancellationSourceInvoiceByRequestId(16L)).thenReturn(Optional.of(sourceInvoice));
        when(invoiceService.findAllocations(260L)).thenReturn(List.of(sourceAllocation));
        when(cancellationPolicyService.evaluateReservation(reservation, null))
                .thenReturn(new CancellationPolicyService.ReservationCancellationEvaluation(
                        new BigDecimal("100.00"),
                        new BigDecimal("50.00"),
                        new BigDecimal("50.00"),
                        "CASH_REFUND",
                        reservation.getStartsAt().minusDays(14),
                        506L,
                        "BEFORE_CUTOFF"
                ));
        when(cancellationRequestRepo.save(any(CancellationRequest.class))).thenAnswer(invocation -> {
            CancellationRequest saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(905L);
            }
            return saved;
        });
        when(invoiceService.createCreditNoteInvoice(260L)).thenReturn(creditNote);
        when(paymentTransactionService.create(any())).thenReturn(PaymentTransactionDto.builder().id(361L).build());
        when(paymentTransactionService.requireById(361L)).thenReturn(refundTransaction);
        when(allocationRepo.findByReservationIdIn(List.of(26L))).thenReturn(List.of(allocation));

        CancellationExecuteRequest executeRequest = new CancellationExecuteRequest();
        executeRequest.setSettlementMode("CASH_REFUND");

        CancellationRequestDto result = service.execute(16L, executeRequest);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getRefundPaymentTransactionId()).isEqualTo(361L);
        verify(paymentProviderClient, never()).refund(any());

        ArgumentCaptor<com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest> paymentCaptor =
                ArgumentCaptor.forClass(com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest.class);
        verify(paymentTransactionService).create(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("-50.00");
        assertThat(paymentCaptor.getValue().getCreditNoteInvoiceId()).isEqualTo(261L);
        assertThat(paymentCaptor.getValue().getSourcePaymentTransactionId()).isEqualTo(360L);
        assertThat(paymentCaptor.getValue().getExternalRef()).isNull();
        assertThat(paymentCaptor.getValue().getNote()).isEqualTo("Cancellation refund handled manually (no provider payment intent link)");
        verify(invoiceService, never()).createPenaltyInvoice(16L, new BigDecimal("50.00"), "EUR");
        verify(eventPublisher, times(1)).publishEvent(any(InvoiceAutoFiscalizationRequestedEvent.class));
    }

    @Test
    void executeCustomerCreditCreatesStornoAndLeavesReusableCredit() {
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(11L)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .build();
        Reservation reservation = Reservation.builder()
                .id(21L)
                .tenantId(1L)
                .startsAt(LocalDateTime.now().plusDays(20))
                .status("CONFIRMED")
                .currency("EUR")
                .grossAmount(new BigDecimal("100.00"))
                .build();
        Allocation allocation = Allocation.builder()
                .id(31L)
                .status("CONFIRMED")
                .reservation(reservation)
                .build();
        Invoice sourceInvoice = Invoice.builder()
                .id(210L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .currency("EUR")
                .totalGross(new BigDecimal("100.00"))
                .build();
        PaymentTransaction sourceCharge = PaymentTransaction.builder()
                .id(310L)
                .tenantId(1L)
                .paymentIntentId(410L)
                .transactionType("CHARGE")
                .paymentType("CARD")
                .status("POSTED")
                .currency("EUR")
                .amount(new BigDecimal("100.00"))
                .build();
        InvoicePaymentAllocation sourceAllocation = InvoicePaymentAllocation.builder()
                .id(510L)
                .invoice(sourceInvoice)
                .paymentTransaction(sourceCharge)
                .allocatedAmount(new BigDecimal("100.00"))
                .allocationType("SETTLEMENT")
                .build();
        Invoice stornoInvoice = Invoice.builder()
                .id(211L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT_STORNO)
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .totalGross(new BigDecimal("-100.00"))
                .build();

        when(requestRepo.findById(11L)).thenReturn(Optional.of(reservationRequest));
        when(cancellationRequestRepo.findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(11L)).thenReturn(Optional.empty());
        when(reservationRepo.findByRequestId(11L)).thenReturn(List.of(reservation));
        when(invoiceService.findCancellationSourceInvoiceByRequestId(11L)).thenReturn(Optional.of(sourceInvoice));
        when(invoiceService.findAllocations(210L)).thenReturn(List.of(sourceAllocation));
        when(cancellationPolicyService.evaluateReservation(reservation, null))
                .thenReturn(new CancellationPolicyService.ReservationCancellationEvaluation(
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        "CUSTOMER_CREDIT",
                        reservation.getStartsAt().minusDays(14),
                        502L,
                        "BEFORE_CUTOFF"
                ));
        when(cancellationRequestRepo.save(any(CancellationRequest.class))).thenAnswer(invocation -> {
            CancellationRequest saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(901L);
            }
            return saved;
        });
        when(invoiceService.createStornoInvoice(210L)).thenReturn(stornoInvoice);
        when(allocationRepo.findByReservationIdIn(List.of(21L))).thenReturn(List.of(allocation));

        CancellationExecuteRequest executeRequest = new CancellationExecuteRequest();
        executeRequest.setSettlementMode("CUSTOMER_CREDIT");

        CancellationRequestDto result = service.execute(11L, executeRequest);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getStornoInvoiceId()).isEqualTo(211L);
        assertThat(result.getCreditAmount()).isEqualByComparingTo("100.00");
        assertThat(result.getRefundPaymentTransactionId()).isNull();
        verify(paymentProviderClient, never()).refund(any());
        verify(paymentTransactionService, never()).create(any());
        verify(eventPublisher, times(1)).publishEvent(any(InvoiceAutoFiscalizationRequestedEvent.class));
    }

    @Test
    void executeNoneCreatesPenaltyInvoiceForPaidDepositAmount() {
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(12L)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .build();
        Reservation reservation = Reservation.builder()
                .id(22L)
                .tenantId(1L)
                .startsAt(LocalDateTime.now().plusDays(20))
                .status("CONFIRMED")
                .currency("EUR")
                .grossAmount(new BigDecimal("140.00"))
                .build();
        Allocation allocation = Allocation.builder()
                .id(32L)
                .status("CONFIRMED")
                .reservation(reservation)
                .build();
        Invoice sourceInvoice = Invoice.builder()
                .id(220L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .currency("EUR")
                .totalGross(new BigDecimal("70.00"))
                .build();
        PaymentTransaction sourceCharge = PaymentTransaction.builder()
                .id(320L)
                .tenantId(1L)
                .transactionType("CHARGE")
                .paymentType("CARD")
                .status("POSTED")
                .currency("EUR")
                .amount(new BigDecimal("70.00"))
                .build();
        InvoicePaymentAllocation sourceAllocation = InvoicePaymentAllocation.builder()
                .id(520L)
                .invoice(sourceInvoice)
                .paymentTransaction(sourceCharge)
                .allocatedAmount(new BigDecimal("70.00"))
                .allocationType("SETTLEMENT")
                .build();
        Invoice stornoInvoice = Invoice.builder()
                .id(221L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT_STORNO)
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .totalGross(new BigDecimal("-70.00"))
                .build();
        Invoice penaltyInvoice = Invoice.builder()
                .id(222L)
                .tenantId(1L)
                .invoiceType(InvoiceType.INVOICE)
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .totalGross(new BigDecimal("70.00"))
                .build();

        when(requestRepo.findById(12L)).thenReturn(Optional.of(reservationRequest));
        when(cancellationRequestRepo.findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(12L)).thenReturn(Optional.empty());
        when(reservationRepo.findByRequestId(12L)).thenReturn(List.of(reservation));
        when(invoiceService.findCancellationSourceInvoiceByRequestId(12L)).thenReturn(Optional.of(sourceInvoice));
        when(invoiceService.findAllocations(220L)).thenReturn(List.of(sourceAllocation));
        when(cancellationPolicyService.evaluateReservation(reservation, null))
                .thenReturn(new CancellationPolicyService.ReservationCancellationEvaluation(
                        new BigDecimal("140.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("140.00"),
                        "NONE",
                        reservation.getStartsAt().minusDays(14),
                        null,
                        "AFTER_CUTOFF"
                ));
        when(cancellationRequestRepo.save(any(CancellationRequest.class))).thenAnswer(invocation -> {
            CancellationRequest saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(902L);
            }
            return saved;
        });
        when(invoiceService.createStornoInvoice(220L)).thenReturn(stornoInvoice);
        when(invoiceService.createPenaltyInvoice(12L, new BigDecimal("70.00"), "EUR")).thenReturn(penaltyInvoice);
        when(allocationRepo.findByReservationIdIn(List.of(22L))).thenReturn(List.of(allocation));

        CancellationExecuteRequest executeRequest = new CancellationExecuteRequest();
        executeRequest.setSettlementMode("NONE");

        CancellationRequestDto result = service.execute(12L, executeRequest);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getFinalInvoiceId()).isNull();
        assertThat(result.getPenaltyInvoiceId()).isEqualTo(222L);
        verify(invoiceService).allocatePaymentToInvoice(222L, 320L, new BigDecimal("70.00"), "SETTLEMENT");
        verify(paymentProviderClient, never()).refund(any());
        verify(paymentTransactionService, never()).create(any());
        verify(eventPublisher, times(2)).publishEvent(any(InvoiceAutoFiscalizationRequestedEvent.class));
    }

    @Test
    void executeNoneAllowsAdminOverrideWhenPolicyWouldOtherwiseReleaseCredit() {
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(13L)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .build();
        Reservation reservation = Reservation.builder()
                .id(23L)
                .tenantId(1L)
                .startsAt(LocalDateTime.now().plusDays(20))
                .status("CONFIRMED")
                .currency("EUR")
                .grossAmount(new BigDecimal("60.00"))
                .build();
        Allocation allocation = Allocation.builder()
                .id(33L)
                .status("CONFIRMED")
                .reservation(reservation)
                .build();
        Invoice sourceInvoice = Invoice.builder()
                .id(230L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .currency("EUR")
                .totalGross(new BigDecimal("30.00"))
                .build();
        PaymentTransaction sourceCharge = PaymentTransaction.builder()
                .id(330L)
                .tenantId(1L)
                .transactionType("CHARGE")
                .paymentType("CARD")
                .status("POSTED")
                .currency("EUR")
                .amount(new BigDecimal("30.00"))
                .build();
        InvoicePaymentAllocation sourceAllocation = InvoicePaymentAllocation.builder()
                .id(530L)
                .invoice(sourceInvoice)
                .paymentTransaction(sourceCharge)
                .allocatedAmount(new BigDecimal("30.00"))
                .allocationType("SETTLEMENT")
                .build();
        Invoice stornoInvoice = Invoice.builder()
                .id(231L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT_STORNO)
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .totalGross(new BigDecimal("-30.00"))
                .build();
        Invoice penaltyInvoice = Invoice.builder()
                .id(232L)
                .tenantId(1L)
                .invoiceType(InvoiceType.INVOICE)
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .totalGross(new BigDecimal("30.00"))
                .build();

        when(requestRepo.findById(13L)).thenReturn(Optional.of(reservationRequest));
        when(cancellationRequestRepo.findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(13L)).thenReturn(Optional.empty());
        when(reservationRepo.findByRequestId(13L)).thenReturn(List.of(reservation));
        when(invoiceService.findCancellationSourceInvoiceByRequestId(13L)).thenReturn(Optional.of(sourceInvoice));
        when(invoiceService.findAllocations(230L)).thenReturn(List.of(sourceAllocation));
        when(cancellationPolicyService.evaluateReservation(reservation, null))
                .thenReturn(new CancellationPolicyService.ReservationCancellationEvaluation(
                        new BigDecimal("60.00"),
                        new BigDecimal("60.00"),
                        BigDecimal.ZERO,
                        "CUSTOMER_CREDIT",
                        reservation.getStartsAt().minusDays(14),
                        503L,
                        "BEFORE_CUTOFF"
                ));
        when(cancellationRequestRepo.save(any(CancellationRequest.class))).thenAnswer(invocation -> {
            CancellationRequest saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(903L);
            }
            return saved;
        });
        when(invoiceService.createStornoInvoice(230L)).thenReturn(stornoInvoice);
        when(invoiceService.createPenaltyInvoice(13L, new BigDecimal("30.00"), "EUR")).thenReturn(penaltyInvoice);
        when(allocationRepo.findByReservationIdIn(List.of(23L))).thenReturn(List.of(allocation));

        CancellationExecuteRequest executeRequest = new CancellationExecuteRequest();
        executeRequest.setSettlementMode("NONE");

        CancellationRequestDto result = service.execute(13L, executeRequest);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getFinalInvoiceId()).isNull();
        assertThat(result.getPenaltyInvoiceId()).isEqualTo(232L);
        verify(invoiceService).allocatePaymentToInvoice(232L, 330L, new BigDecimal("30.00"), "SETTLEMENT");
        verify(paymentProviderClient, never()).refund(any());
        verify(paymentTransactionService, never()).create(any());
        verify(eventPublisher, times(2)).publishEvent(any(InvoiceAutoFiscalizationRequestedEvent.class));
    }

    @Test
    void previewPublicReturnsPolicyDrivenOutcome() {
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(14L)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .cancellationPolicyText("Free cancellation until 2026-04-15 10:00.")
                .build();
        Reservation reservation = Reservation.builder()
                .id(24L)
                .tenantId(1L)
                .startsAt(LocalDateTime.of(2026, 4, 29, 10, 0))
                .status("CONFIRMED")
                .currency("EUR")
                .grossAmount(new BigDecimal("120.00"))
                .build();
        Invoice sourceInvoice = Invoice.builder()
                .id(240L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .currency("EUR")
                .totalGross(new BigDecimal("60.00"))
                .build();
        PaymentTransaction sourceCharge = PaymentTransaction.builder()
                .id(340L)
                .tenantId(1L)
                .transactionType("CHARGE")
                .paymentType("CARD")
                .status("POSTED")
                .currency("EUR")
                .amount(new BigDecimal("60.00"))
                .build();
        InvoicePaymentAllocation sourceAllocation = InvoicePaymentAllocation.builder()
                .id(540L)
                .invoice(sourceInvoice)
                .paymentTransaction(sourceCharge)
                .allocatedAmount(new BigDecimal("60.00"))
                .allocationType("SETTLEMENT")
                .build();

        when(requestRepo.findById(14L)).thenReturn(Optional.of(reservationRequest));
        when(cancellationRequestRepo.findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(14L)).thenReturn(Optional.empty());
        when(reservationRepo.findByRequestId(14L)).thenReturn(List.of(reservation));
        when(invoiceService.findCancellationSourceInvoiceByRequestId(14L)).thenReturn(Optional.of(sourceInvoice));
        when(invoiceService.findAllocations(240L)).thenReturn(List.of(sourceAllocation));
        when(cancellationPolicyService.evaluateReservation(reservation, null))
                .thenReturn(new CancellationPolicyService.ReservationCancellationEvaluation(
                        new BigDecimal("120.00"),
                        new BigDecimal("60.00"),
                        new BigDecimal("60.00"),
                        "CASH_REFUND",
                        LocalDateTime.of(2026, 4, 15, 10, 0),
                        504L,
                        "BEFORE_CUTOFF"
                ));

        PublicCancellationPreviewDto preview = service.previewPublic(14L);

        assertThat(preview.isCanCancel()).isTrue();
        assertThat(preview.getStatus()).isEqualTo("AVAILABLE");
        assertThat(preview.getSettlementMode()).isEqualTo("CASH_REFUND");
        assertThat(preview.getRefundAmount()).isEqualByComparingTo("60.00");
        assertThat(preview.getPenaltyAmount()).isEqualByComparingTo("0.00");
        assertThat(preview.getFreeCancellationUntil()).isEqualTo(LocalDateTime.of(2026, 4, 15, 10, 0));
    }

    @Test
    void executePublicIgnoresRequestedSettlementModeAndUsesPolicyOutcome() {
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(15L)
                .tenantId(1L)
                .status(ReservationRequest.Status.FINALIZED)
                .build();
        Reservation reservation = Reservation.builder()
                .id(25L)
                .tenantId(1L)
                .startsAt(LocalDateTime.now().plusDays(20))
                .status("CONFIRMED")
                .currency("EUR")
                .grossAmount(new BigDecimal("100.00"))
                .build();
        Allocation allocation = Allocation.builder()
                .id(35L)
                .status("CONFIRMED")
                .reservation(reservation)
                .build();
        Invoice sourceInvoice = Invoice.builder()
                .id(250L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .currency("EUR")
                .totalGross(new BigDecimal("100.00"))
                .build();
        PaymentTransaction sourceCharge = PaymentTransaction.builder()
                .id(350L)
                .tenantId(1L)
                .paymentIntentId(450L)
                .transactionType("CHARGE")
                .paymentType("CARD")
                .cardType("VISA")
                .status("POSTED")
                .currency("EUR")
                .amount(new BigDecimal("100.00"))
                .build();
        InvoicePaymentAllocation sourceAllocation = InvoicePaymentAllocation.builder()
                .id(550L)
                .invoice(sourceInvoice)
                .paymentTransaction(sourceCharge)
                .allocatedAmount(new BigDecimal("100.00"))
                .allocationType("SETTLEMENT")
                .build();
        Invoice stornoInvoice = Invoice.builder()
                .id(251L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT_STORNO)
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .totalGross(new BigDecimal("-100.00"))
                .build();

        when(requestRepo.findById(15L)).thenReturn(Optional.of(reservationRequest));
        when(cancellationRequestRepo.findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(15L)).thenReturn(Optional.empty());
        when(reservationRepo.findByRequestId(15L)).thenReturn(List.of(reservation));
        when(invoiceService.findCancellationSourceInvoiceByRequestId(15L)).thenReturn(Optional.of(sourceInvoice));
        when(invoiceService.findAllocations(250L)).thenReturn(List.of(sourceAllocation));
        when(cancellationPolicyService.evaluateReservation(reservation, null))
                .thenReturn(new CancellationPolicyService.ReservationCancellationEvaluation(
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        "CUSTOMER_CREDIT",
                        reservation.getStartsAt().minusDays(14),
                        505L,
                        "BEFORE_CUTOFF"
                ));
        when(cancellationRequestRepo.save(any(CancellationRequest.class))).thenAnswer(invocation -> {
            CancellationRequest saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(904L);
            }
            return saved;
        });
        when(invoiceService.createStornoInvoice(250L)).thenReturn(stornoInvoice);
        when(allocationRepo.findByReservationIdIn(List.of(25L))).thenReturn(List.of(allocation));

        CancellationExecuteRequest executeRequest = new CancellationExecuteRequest();
        executeRequest.setSettlementMode("NONE");
        executeRequest.setNote("Public cancellation");

        CancellationRequestDto result = service.executePublic(15L, executeRequest);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getSettlementMode()).isEqualTo("CUSTOMER_CREDIT");
        assertThat(result.getCreditAmount()).isEqualByComparingTo("100.00");
        verify(invoiceService, times(1)).createStornoInvoice(250L);
        verify(paymentProviderClient, never()).refund(any());
        verify(paymentTransactionService, never()).create(any());
        verify(eventPublisher, times(1)).publishEvent(any(InvoiceAutoFiscalizationRequestedEvent.class));
    }
}
