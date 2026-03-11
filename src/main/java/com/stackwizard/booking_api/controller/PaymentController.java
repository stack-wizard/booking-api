package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.PaymentInitiateRequest;
import com.stackwizard.booking_api.dto.PaymentInitiateResponse;
import com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest;
import com.stackwizard.booking_api.dto.PaymentTransactionDto;
import com.stackwizard.booking_api.dto.PaymentTransactionSearchCriteria;
import com.stackwizard.booking_api.model.PaymentEvent;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.service.PaymentService;
import com.stackwizard.booking_api.service.PaymentTransactionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping({"/api/payments", "/booking-api/api/payments"})
public class PaymentController {
    private static final int MAX_PAGE_SIZE = 200;

    private final PaymentService paymentService;
    private final PaymentTransactionService paymentTransactionService;

    public PaymentController(PaymentService paymentService,
                             PaymentTransactionService paymentTransactionService) {
        this.paymentService = paymentService;
        this.paymentTransactionService = paymentTransactionService;
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

    @GetMapping("/transactions")
    public Page<PaymentTransactionDto> transactions(PaymentTransactionSearchCriteria criteria,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(defaultValue = "createdAt") String sortBy,
                                                    @RequestParam(defaultValue = "desc") String sortDir) {
        Pageable pageable = buildPageable(page, size, sortBy, sortDir);
        return paymentTransactionService.search(criteria, pageable);
    }

    @GetMapping("/transactions/{id}")
    public ResponseEntity<PaymentTransactionDto> transactionById(@PathVariable Long id) {
        return paymentTransactionService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/transactions")
    public ResponseEntity<PaymentTransactionDto> createTransaction(@RequestBody PaymentTransactionCreateRequest request) {
        return ResponseEntity.ok(paymentTransactionService.create(request));
    }

    @PostMapping("/providers/monri/webhook/{tenantId}")
    public ResponseEntity<Void> monriWebhook(@PathVariable Long tenantId,
                                             @RequestHeader(value = "X-Callback-Token", required = false) String callbackToken,
                                             @RequestBody String payload) {
        paymentService.processMonriWebhook(tenantId, payload, callbackToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/providers/monri/callback/{tenantId}")
    public ResponseEntity<Void> monriCallback(@PathVariable Long tenantId,
                                              @RequestHeader(value = "X-Callback-Token", required = false) String callbackToken,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestHeader(value = "http_authorization", required = false) String httpAuthorization,
                                              @RequestBody String payload) {
        paymentService.processMonriCallback(tenantId, payload, callbackToken, authorization, httpAuthorization);
        return ResponseEntity.noContent().build();
    }

    private Pageable buildPageable(int page, int size, String sortBy, String sortDir) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String sortProperty = resolveSortProperty(sortBy);
        Sort.Direction direction = resolveDirection(sortDir);
        return PageRequest.of(page, size, Sort.by(direction, sortProperty));
    }

    private String resolveSortProperty(String sortBy) {
        String raw = sortBy == null ? "createdAt" : sortBy.trim();
        String normalized = raw.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "id", "transactionid", "paymenttransactionid" -> "id";
            case "createdat", "created_at" -> "createdAt";
            case "amount" -> "amount";
            case "paymenttype", "payment_type" -> "paymentType";
            case "status" -> "status";
            case "currency" -> "currency";
            case "reservationrequestid", "reservation_request_id", "reservationrequest", "reservation_reqst", "reservation_request" -> "reservationRequestId";
            case "paymentintentid", "payment_intent_id", "paymentintent" -> "paymentIntentId";
            default -> throw new IllegalArgumentException("Unsupported sortBy value: " + sortBy);
        };
    }

    private Sort.Direction resolveDirection(String sortDir) {
        String normalized = sortDir == null ? "desc" : sortDir.trim().toLowerCase(Locale.ROOT);
        if ("asc".equals(normalized)) {
            return Sort.Direction.ASC;
        }
        if ("desc".equals(normalized)) {
            return Sort.Direction.DESC;
        }
        throw new IllegalArgumentException("sortDir must be ASC or DESC");
    }
}
