package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.service.PaymentService;
import com.stackwizard.booking_api.service.PaymentTransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;
    @Mock
    private PaymentTransactionService paymentTransactionService;

    @Test
    void markReservationRequestPaidDelegatesToPaymentService() {
        PaymentController controller = new PaymentController(paymentService, paymentTransactionService);
        PaymentIntent intent = PaymentIntent.builder()
                .id(55L)
                .status("PAID")
                .build();

        when(paymentService.markReservationRequestPaidManually(77L)).thenReturn(intent);

        ResponseEntity<PaymentIntent> response = controller.markReservationRequestPaid(77L);

        assertThat(response.getBody()).isSameAs(intent);
        verify(paymentService).markReservationRequestPaidManually(77L);
    }
}
