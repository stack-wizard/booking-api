package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.PaymentInitiateRequest;
import com.stackwizard.booking_api.dto.PaymentInitiateResponse;
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
}
