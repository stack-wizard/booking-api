package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.PaymentInitiateRequest;
import com.stackwizard.booking_api.dto.PaymentInitiateResponse;
import com.stackwizard.booking_api.model.PaymentEvent;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/reservation-requests/{reservationRequestId}/initiate")
    public ResponseEntity<PaymentInitiateResponse> initiate(@PathVariable Long reservationRequestId,
                                                            @RequestBody(required = false) PaymentInitiateRequest request) {
        return ResponseEntity.ok(paymentService.initiateForReservationRequest(reservationRequestId, request));
    }

    @GetMapping("/reservation-requests/{reservationRequestId}/intents")
    public List<PaymentIntent> intents(@PathVariable Long reservationRequestId) {
        return paymentService.findByReservationRequestId(reservationRequestId);
    }

    @GetMapping("/reservation-requests/{reservationRequestId}/events")
    public List<PaymentEvent> eventsByReservationRequest(@PathVariable Long reservationRequestId) {
        return paymentService.findEventsByReservationRequestId(reservationRequestId);
    }

    @GetMapping("/intents/{paymentIntentId}/events")
    public List<PaymentEvent> eventsByPaymentIntent(@PathVariable Long paymentIntentId) {
        return paymentService.findEventsByPaymentIntentId(paymentIntentId);
    }

    @PostMapping("/intents/{paymentIntentId}/processing")
    public ResponseEntity<PaymentIntent> markProcessing(@PathVariable Long paymentIntentId) {
        return ResponseEntity.ok(paymentService.markIntentProcessing(paymentIntentId));
    }

    @PostMapping("/providers/monri/webhook/{tenantId}")
    public ResponseEntity<Void> monriWebhook(@PathVariable Long tenantId,
                                             @RequestHeader(value = "X-Callback-Token", required = false) String callbackToken,
                                             @RequestBody String payload) {
        paymentService.processMonriWebhook(tenantId, payload, callbackToken);
        return ResponseEntity.noContent().build();
    }
}
