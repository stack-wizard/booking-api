package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationRequestServiceTest {

    @Mock
    private ReservationRequestRepository requestRepo;
    @Mock
    private ReservationRepository reservationRepo;
    @Mock
    private AllocationRepository allocationRepo;
    @Mock
    private PaymentIntentRepository paymentIntentRepo;
    @Mock
    private ReservationRequestAccessTokenService accessTokenService;
    @Mock
    private ReservationService reservationService;
    @Mock
    private TenantConfigService tenantConfigService;

    private ReservationRequestService service;

    @BeforeEach
    void setUp() {
        service = new ReservationRequestService(
                requestRepo,
                reservationRepo,
                allocationRepo,
                paymentIntentRepo,
                accessTokenService,
                reservationService,
                tenantConfigService
        );
    }

    @Test
    void expireStaleRequestsMovesProcessingPendingPaymentToManualReview() {
        Long requestId = 10L;
        Long tenantId = 7L;
        ReservationRequest request = ReservationRequest.builder()
                .id(requestId)
                .tenantId(tenantId)
                .status(ReservationRequest.Status.PENDING_PAYMENT)
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .build();
        PaymentIntent intent = PaymentIntent.builder()
                .id(100L)
                .reservationRequestId(requestId)
                .status("PROCESSING")
                .build();

        when(requestRepo.findLockedByStatusInAndExpiresAtBefore(anyList(), any())).thenReturn(List.of(request));
        when(paymentIntentRepo.findLockedByReservationRequestIdOrderByCreatedAtDesc(requestId)).thenReturn(List.of(intent));
        when(tenantConfigService.manualReviewTtlMinutes(tenantId)).thenReturn(2880);
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationService.synchronizeRequestExpiry(eq(requestId), any())).thenAnswer(invocation -> request);

        OffsetDateTime before = OffsetDateTime.now();
        service.expireStaleRequests();
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(request.getStatus()).isEqualTo(ReservationRequest.Status.MANUAL_REVIEW);
        assertThat(request.getExpiresAt()).isAfterOrEqualTo(before.plusMinutes(2880));
        assertThat(request.getExpiresAt()).isBeforeOrEqualTo(after.plusMinutes(2880).plusSeconds(1));

        verify(paymentIntentRepo, never()).saveAll(anyList());
        verify(reservationService).synchronizeRequestExpiry(eq(requestId), eq(request.getExpiresAt()));
    }

    @Test
    void expireStaleRequestsExpiresPendingPaymentWithoutProcessing() {
        Long requestId = 11L;
        ReservationRequest request = ReservationRequest.builder()
                .id(requestId)
                .tenantId(7L)
                .status(ReservationRequest.Status.PENDING_PAYMENT)
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .build();
        PaymentIntent intent = PaymentIntent.builder()
                .id(101L)
                .reservationRequestId(requestId)
                .status("PENDING_CUSTOMER")
                .build();

        when(requestRepo.findLockedByStatusInAndExpiresAtBefore(anyList(), any())).thenReturn(List.of(request));
        when(paymentIntentRepo.findLockedByReservationRequestIdOrderByCreatedAtDesc(requestId)).thenReturn(List.of(intent));
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentIntentRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationService.synchronizeRequestExpiry(eq(requestId), any())).thenAnswer(invocation -> request);

        service.expireStaleRequests();

        assertThat(request.getStatus()).isEqualTo(ReservationRequest.Status.EXPIRED);
        assertThat(intent.getStatus()).isEqualTo("EXPIRED");
        assertThat(intent.getCompletedAt()).isNotNull();

        ArgumentCaptor<OffsetDateTime> expiresAtCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(reservationService).synchronizeRequestExpiry(eq(requestId), expiresAtCaptor.capture());
        assertThat(expiresAtCaptor.getValue()).isNotNull();
        assertThat(expiresAtCaptor.getValue()).isBeforeOrEqualTo(OffsetDateTime.now());
        verify(paymentIntentRepo).saveAll(List.of(intent));
    }

    @Test
    void expireStaleRequestsExpiresManualReviewRequestAndActiveIntent() {
        Long requestId = 12L;
        ReservationRequest request = ReservationRequest.builder()
                .id(requestId)
                .tenantId(7L)
                .status(ReservationRequest.Status.MANUAL_REVIEW)
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .build();
        PaymentIntent intent = PaymentIntent.builder()
                .id(102L)
                .reservationRequestId(requestId)
                .status("PROCESSING")
                .build();

        when(requestRepo.findLockedByStatusInAndExpiresAtBefore(anyList(), any())).thenReturn(List.of(request));
        when(paymentIntentRepo.findLockedByReservationRequestIdOrderByCreatedAtDesc(requestId)).thenReturn(List.of(intent));
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentIntentRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationService.synchronizeRequestExpiry(eq(requestId), any())).thenAnswer(invocation -> request);

        service.expireStaleRequests();

        assertThat(request.getStatus()).isEqualTo(ReservationRequest.Status.EXPIRED);
        assertThat(intent.getStatus()).isEqualTo("EXPIRED");
        verify(paymentIntentRepo).saveAll(List.of(intent));
    }

    @Test
    void expireStaleRequestsExpiresDraftRequest() {
        Long requestId = 13L;
        ReservationRequest request = ReservationRequest.builder()
                .id(requestId)
                .tenantId(7L)
                .status(ReservationRequest.Status.DRAFT)
                .expiresAt(OffsetDateTime.now().minusMinutes(1))
                .build();

        when(requestRepo.findLockedByStatusInAndExpiresAtBefore(anyList(), any())).thenReturn(List.of(request));
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationService.synchronizeRequestExpiry(eq(requestId), any())).thenAnswer(invocation -> request);

        service.expireStaleRequests();

        assertThat(request.getStatus()).isEqualTo(ReservationRequest.Status.EXPIRED);
        verify(paymentIntentRepo, never()).findLockedByReservationRequestIdOrderByCreatedAtDesc(any());
    }

    @Test
    void replaceDraftReservationRequestSetsCustomerCountry() {
        Long requestId = 20L;
        ReservationRequest existing = ReservationRequest.builder()
                .id(requestId)
                .tenantId(7L)
                .type(ReservationRequest.Type.EXTERNAL)
                .status(ReservationRequest.Status.DRAFT)
                .extensionCount(0)
                .build();
        ReservationRequest incoming = ReservationRequest.builder()
                .tenantId(7L)
                .type(ReservationRequest.Type.EXTERNAL)
                .status(ReservationRequest.Status.DRAFT)
                .customerCountry("us")
                .build();

        when(requestRepo.findById(requestId)).thenReturn(Optional.of(existing));
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReservationRequest updated = service.replaceDraftReservationRequest(requestId, incoming);

        assertThat(updated.getCustomerCountry()).isEqualTo("US");
    }
}
