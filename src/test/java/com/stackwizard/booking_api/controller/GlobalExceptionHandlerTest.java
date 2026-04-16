package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CheckoutConflictError;
import com.stackwizard.booking_api.exception.CheckoutBlockedException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    @Test
    void checkoutBlockedReturnsConflictWithBlockers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/reservation-requests/1/check-out");

        ResponseEntity<CheckoutConflictError> response = handler.handleCheckoutBlocked(
                new CheckoutBlockedException(List.of("Invoice X is draft", "Invoice Y is UNPAID")),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().path()).isEqualTo("/api/reservation-requests/1/check-out");
        assertThat(response.getBody().blockers()).containsExactly("Invoice X is draft", "Invoice Y is UNPAID");
    }
}
