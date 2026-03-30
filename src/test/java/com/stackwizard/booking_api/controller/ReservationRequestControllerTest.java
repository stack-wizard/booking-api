package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.service.ReservationConfirmationEmailService;
import com.stackwizard.booking_api.service.ReservationRequestDtoMapper;
import com.stackwizard.booking_api.service.ReservationRequestService;
import com.stackwizard.booking_api.service.ReservationService;
import com.stackwizard.booking_api.service.TenantConfigService;
import com.stackwizard.booking_api.service.CancellationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationRequestControllerTest {

    @Mock
    private ReservationRequestService requestService;
    @Mock
    private ReservationService reservationService;
    @Mock
    private ObjectProvider<ReservationConfirmationEmailService> confirmationEmailServiceProvider;
    @Mock
    private TenantConfigService tenantConfigService;
    @Mock
    private ReservationRequestDtoMapper dtoMapper;
    @Mock
    private CancellationService cancellationService;

    @Test
    void createKeepsInternalDraftRequestWithoutExpiry() {
        ReservationRequestController controller = new ReservationRequestController(
                requestService,
                reservationService,
                cancellationService,
                confirmationEmailServiceProvider,
                tenantConfigService,
                dtoMapper
        );

        ReservationRequest request = ReservationRequest.builder()
                .tenantId(7L)
                .type(ReservationRequest.Type.INTERNAL)
                .status(ReservationRequest.Status.DRAFT)
                .build();

        when(requestService.save(any(ReservationRequest.class))).thenAnswer(invocation -> {
            ReservationRequest saved = invocation.getArgument(0);
            saved.setId(44L);
            return saved;
        });

        ResponseEntity<ReservationRequest> response = controller.create(request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getExpiresAt()).isNull();
        assertThat(response.getBody().getExtensionCount()).isZero();
        verify(requestService).save(request);
        verifyNoInteractions(tenantConfigService);
    }
}
