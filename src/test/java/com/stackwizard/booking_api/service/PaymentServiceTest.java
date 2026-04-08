package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.PaymentInitiateRequest;
import com.stackwizard.booking_api.dto.PaymentInitiateResponse;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.PaymentEvent;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.DepositPolicyRepository;
import com.stackwizard.booking_api.repository.PaymentEventRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.payment.MonriTenantConfigResolver;
import com.stackwizard.booking_api.service.payment.PaymentProviderClient;
import com.stackwizard.booking_api.service.payment.PaymentProviderInitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentIntentRepository paymentIntentRepo;
    @Mock
    private PaymentEventRepository paymentEventRepo;
    @Mock
    private ReservationRequestRepository requestRepo;
    @Mock
    private ReservationRepository reservationRepo;
    @Mock
    private DepositPolicyRepository depositPolicyRepo;
    @Mock
    private ReservationService reservationService;
    @Mock
    private InvoiceService invoiceService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private MonriTenantConfigResolver monriTenantConfigResolver;
    @Mock
    private Environment environment;
    @Mock
    private PaymentProviderClient providerClient;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        when(providerClient.providerCode()).thenReturn("MONRI");
        service = new PaymentService(
                paymentIntentRepo,
                paymentEventRepo,
                requestRepo,
                reservationRepo,
                depositPolicyRepo,
                reservationService,
                invoiceService,
                eventPublisher,
                monriTenantConfigResolver,
                environment,
                List.of(providerClient)
        );
    }

    @Test
    void initiateForReservationRequestSynchronizesRequestExpiryWithIntentExpiry() {
        Long requestId = 77L;
        Long tenantId = 8L;
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(requestId)
                .tenantId(tenantId)
                .type(ReservationRequest.Type.EXTERNAL)
                .status(ReservationRequest.Status.DRAFT)
                .build();
        Reservation reservation = Reservation.builder()
                .id(88L)
                .tenantId(tenantId)
                .productId(99L)
                .status("HOLD")
                .currency("EUR")
                .grossAmount(new BigDecimal("120.00"))
                .build();

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(reservationRequest));
        when(reservationRepo.findByRequestId(requestId)).thenReturn(List.of(reservation));
        when(depositPolicyRepo.findByTenantId(tenantId)).thenReturn(List.of());
        when(paymentIntentRepo.findLockedByReservationRequestIdAndStatusInOrderByCreatedAtDesc(eq(requestId), anyList()))
                .thenReturn(List.of());
        when(paymentIntentRepo.save(any(PaymentIntent.class))).thenAnswer(invocation -> {
            PaymentIntent intent = invocation.getArgument(0);
            if (intent.getId() == null) {
                intent.setId(123L);
            }
            return intent;
        });
        when(providerClient.initiate(any(PaymentIntent.class), any(PaymentInitiateRequest.class)))
                .thenReturn(new PaymentProviderInitResult("provider-payment-1", "secret-1", "PENDING"));
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentInitiateResponse response = service.initiateForReservationRequest(requestId, new PaymentInitiateRequest());

        assertThat(response.getStatus()).isEqualTo("PENDING_CUSTOMER");
        assertThat(response.getExpiresAt()).isNotNull();
        assertThat(reservationRequest.getStatus()).isEqualTo(ReservationRequest.Status.PENDING_PAYMENT);

        ArgumentCaptor<OffsetDateTime> expiresAtCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reservationService).synchronizeRequestExpiry(eq(requestId), expiresAtCaptor.capture());
        assertThat(expiresAtCaptor.getValue()).isEqualTo(response.getExpiresAt());
    }

    @Test
    void initiateForReservationRequestRejectsInternalRequests() {
        Long requestId = 78L;
        Long tenantId = 8L;
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(requestId)
                .tenantId(tenantId)
                .type(ReservationRequest.Type.INTERNAL)
                .status(ReservationRequest.Status.DRAFT)
                .build();

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(reservationRequest));

        assertThatThrownBy(() -> service.initiateForReservationRequest(requestId, new PaymentInitiateRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal reservation requests are not payable online");

        verify(paymentIntentRepo, never()).save(any(PaymentIntent.class));
    }

    @Test
    void initiateForReservationRequestRejectsManualReviewRequests() {
        Long requestId = 79L;
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(requestId)
                .tenantId(8L)
                .type(ReservationRequest.Type.EXTERNAL)
                .status(ReservationRequest.Status.MANUAL_REVIEW)
                .build();

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(reservationRequest));

        assertThatThrownBy(() -> service.initiateForReservationRequest(requestId, new PaymentInitiateRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Reservation request is not payable in current status");
    }

    @Test
    void initiateForReservationRequestRejectsExpiredRequests() {
        Long requestId = 80L;
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(requestId)
                .tenantId(8L)
                .type(ReservationRequest.Type.EXTERNAL)
                .status(ReservationRequest.Status.EXPIRED)
                .build();

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(reservationRequest));

        assertThatThrownBy(() -> service.initiateForReservationRequest(requestId, new PaymentInitiateRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Reservation request is not payable in current status");
    }

    @Test
    void processMonriCallbackDoesNotFinalizeExpiredReservationRequest() {
        Long tenantId = 8L;
        PaymentIntent paymentIntent = PaymentIntent.builder()
                .id(55L)
                .tenantId(tenantId)
                .reservationRequestId(77L)
                .provider("MONRI")
                .providerOrderNumber("RR-77-AAAA1111")
                .status("PROCESSING")
                .build();
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(77L)
                .tenantId(tenantId)
                .type(ReservationRequest.Type.EXTERNAL)
                .status(ReservationRequest.Status.EXPIRED)
                .build();
        MonriTenantConfigResolver.MonriResolvedConfig config = new MonriTenantConfigResolver.MonriResolvedConfig(
                "https://example.test",
                "/oauth",
                "/request",
                "/refund",
                "client-id",
                "client-secret",
                "auth-token",
                "callback-token"
        );

        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});
        when(monriTenantConfigResolver.resolve(tenantId)).thenReturn(config);
        when(paymentIntentRepo.findLockedByProviderAndProviderOrderNumber("MONRI", paymentIntent.getProviderOrderNumber()))
                .thenReturn(Optional.of(paymentIntent));
        when(paymentEventRepo.findByProviderAndProviderEventId(eq("MONRI"), any())).thenReturn(Optional.empty());
        when(paymentEventRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(requestRepo.findById(paymentIntent.getReservationRequestId())).thenReturn(Optional.of(reservationRequest));
        when(paymentIntentRepo.save(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.processMonriCallback(
                tenantId,
                "{\"order_number\":\"RR-77-AAAA1111\",\"id\":\"provider-1\"}",
                "callback-token",
                null,
                null
        );

        assertThat(paymentIntent.getStatus()).isEqualTo("PAID");
        verify(reservationService, never()).finalizeRequest(any());
        verify(invoiceService, never()).createDepositInvoiceForPaymentIntent(any());
    }

    @Test
    void markReservationRequestPaidManuallyFinalizesUsingLatestNonSupersededIntent() {
        Long requestId = 81L;
        Long tenantId = 8L;
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(requestId)
                .tenantId(tenantId)
                .type(ReservationRequest.Type.EXTERNAL)
                .status(ReservationRequest.Status.MANUAL_REVIEW)
                .build();
        PaymentIntent supersededIntent = PaymentIntent.builder()
                .id(901L)
                .tenantId(tenantId)
                .reservationRequestId(requestId)
                .status("SUPERSEDED")
                .build();
        PaymentIntent processingIntent = PaymentIntent.builder()
                .id(902L)
                .tenantId(tenantId)
                .reservationRequestId(requestId)
                .status("PROCESSING")
                .build();

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(reservationRequest), Optional.of(reservationRequest));
        when(paymentIntentRepo.findLockedByReservationRequestIdOrderByCreatedAtDesc(requestId))
                .thenReturn(List.of(supersededIntent, processingIntent));
        when(paymentEventRepo.findByProviderAndProviderEventId("MANUAL", "manual-paid|request:81|intent:902"))
                .thenReturn(Optional.empty());
        when(paymentEventRepo.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentIntentRepo.save(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceService.createDepositInvoiceForPaymentIntent(processingIntent)).thenReturn(Invoice.builder().id(77L).build());

        PaymentIntent result = service.markReservationRequestPaidManually(requestId);

        assertThat(result).isSameAs(processingIntent);
        assertThat(processingIntent.getStatus()).isEqualTo("PAID");
        verify(reservationService).finalizeRequest(requestId);
        verify(invoiceService).createDepositInvoiceForPaymentIntent(processingIntent);
    }

    @Test
    void markReservationRequestPaidManuallyIsIdempotentWhenManualEventAlreadyExists() {
        Long requestId = 82L;
        Long tenantId = 8L;
        ReservationRequest reservationRequest = ReservationRequest.builder()
                .id(requestId)
                .tenantId(tenantId)
                .type(ReservationRequest.Type.EXTERNAL)
                .status(ReservationRequest.Status.MANUAL_REVIEW)
                .build();
        PaymentIntent paymentIntent = PaymentIntent.builder()
                .id(903L)
                .tenantId(tenantId)
                .reservationRequestId(requestId)
                .status("PAID")
                .build();

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(reservationRequest));
        when(paymentIntentRepo.findLockedByReservationRequestIdOrderByCreatedAtDesc(requestId))
                .thenReturn(List.of(paymentIntent));
        when(paymentEventRepo.findByProviderAndProviderEventId("MANUAL", "manual-paid|request:82|intent:903"))
                .thenReturn(Optional.of(PaymentEvent.builder().id(1L).provider("MANUAL").eventType("manual:paid").build()));

        PaymentIntent result = service.markReservationRequestPaidManually(requestId);

        assertThat(result).isSameAs(paymentIntent);
        verify(paymentEventRepo, never()).saveAndFlush(any());
        verify(paymentIntentRepo, never()).save(any(PaymentIntent.class));
        verify(reservationService, never()).finalizeRequest(any());
    }
}
