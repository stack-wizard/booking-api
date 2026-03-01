package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.dto.PaymentInitiateRequest;
import com.stackwizard.booking_api.dto.PaymentInitiateResponse;
import com.stackwizard.booking_api.model.DepositPolicy;
import com.stackwizard.booking_api.model.PaymentEvent;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.DepositPolicyRepository;
import com.stackwizard.booking_api.repository.PaymentEventRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.payment.PaymentProviderClient;
import com.stackwizard.booking_api.service.payment.PaymentProviderInitResult;
import com.stackwizard.booking_api.service.payment.PaymentProviderWebhookResult;
import com.stackwizard.booking_api.service.payment.MonriTenantConfigResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PaymentService {
    private final PaymentIntentRepository paymentIntentRepo;
    private final PaymentEventRepository paymentEventRepo;
    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final DepositPolicyRepository depositPolicyRepo;
    private final ReservationService reservationService;
    private final InvoiceService invoiceService;
    private final MonriTenantConfigResolver monriTenantConfigResolver;
    private final Map<String, PaymentProviderClient> providerClients;

    public PaymentService(PaymentIntentRepository paymentIntentRepo,
                          PaymentEventRepository paymentEventRepo,
                          ReservationRequestRepository requestRepo,
                          ReservationRepository reservationRepo,
                          DepositPolicyRepository depositPolicyRepo,
                          ReservationService reservationService,
                          InvoiceService invoiceService,
                          MonriTenantConfigResolver monriTenantConfigResolver,
                          List<PaymentProviderClient> providerClients) {
        this.paymentIntentRepo = paymentIntentRepo;
        this.paymentEventRepo = paymentEventRepo;
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.depositPolicyRepo = depositPolicyRepo;
        this.reservationService = reservationService;
        this.invoiceService = invoiceService;
        this.monriTenantConfigResolver = monriTenantConfigResolver;
        this.providerClients = providerClients.stream()
                .collect(Collectors.toMap(c -> c.providerCode().toUpperCase(Locale.ROOT), c -> c));
    }

    public List<PaymentIntent> findByReservationRequestId(Long reservationRequestId) {
        return paymentIntentRepo.findByReservationRequestIdOrderByCreatedAtDesc(reservationRequestId);
    }

    public List<PaymentEvent> findEventsByPaymentIntentId(Long paymentIntentId) {
        return paymentEventRepo.findByPaymentIntentIdOrderByCreatedAtDesc(paymentIntentId);
    }

    public List<PaymentEvent> findEventsByReservationRequestId(Long reservationRequestId) {
        List<Long> intentIds = paymentIntentRepo.findByReservationRequestIdOrderByCreatedAtDesc(reservationRequestId).stream()
                .map(PaymentIntent::getId)
                .toList();
        if (intentIds.isEmpty()) {
            return List.of();
        }
        return paymentEventRepo.findByPaymentIntentIdInOrderByCreatedAtDesc(intentIds);
    }

    public RequestPaymentSummary summarizeReservationRequest(Long reservationRequestId, Long tenantId, List<Reservation> reservations) {
        BigDecimal totalAmount = reservations.stream()
                .map(this::reservationTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal dueNow = calculateDueNow(tenantId, reservations).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paidAmount = paymentIntentRepo.findByReservationRequestId(reservationRequestId).stream()
                .filter(i -> "PAID".equalsIgnoreCase(i.getStatus()))
                .map(PaymentIntent::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = dueNow.subtract(paidAmount);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            remaining = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        String status = "UNPAID";
        if (paidAmount.compareTo(BigDecimal.ZERO) > 0 && paidAmount.compareTo(dueNow) < 0) {
            status = "PARTIALLY_PAID";
        } else if (dueNow.compareTo(BigDecimal.ZERO) > 0 && paidAmount.compareTo(dueNow) >= 0) {
            status = "PAID";
        }

        return new RequestPaymentSummary(totalAmount, dueNow, paidAmount, remaining, status);
    }

    @Transactional
    public PaymentInitiateResponse initiateForReservationRequest(Long reservationRequestId, PaymentInitiateRequest request) {
        ReservationRequest reservationRequest = requestRepo.findById(reservationRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation request not found"));
        if (reservationRequest.getStatus() == ReservationRequest.Status.CANCELLED
                || reservationRequest.getStatus() == ReservationRequest.Status.FINALIZED) {
            throw new IllegalStateException("Reservation request is not payable in current status");
        }

        String provider = normalizeProvider(request != null ? request.getProvider() : null);
        PaymentProviderClient providerClient = providerClients.get(provider);
        if (providerClient == null) {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }

        List<Reservation> reservations = reservationRepo.findByRequestId(reservationRequestId);
        if (reservations.isEmpty()) {
            throw new IllegalStateException("Reservation request has no reservations");
        }

        String currency = resolveCurrency(reservations);
        BigDecimal dueNowAmount = calculateDueNow(reservationRequest.getTenantId(), reservations);
        if (dueNowAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Computed due amount must be greater than zero");
        }

        String idempotencyKey = "reservation-request:" + reservationRequestId + ":" + provider;
        Optional<PaymentIntent> existing = paymentIntentRepo.findByIdempotencyKey(idempotencyKey)
                .filter(intent -> "PENDING_CUSTOMER".equalsIgnoreCase(intent.getStatus())
                        || "CREATED".equalsIgnoreCase(intent.getStatus()));
        if (existing.isPresent()) {
            PaymentIntent intent = existing.get();
            return toInitiateResponse(intent);
        }

        PaymentIntent paymentIntent = PaymentIntent.builder()
                .tenantId(reservationRequest.getTenantId())
                .reservationRequestId(reservationRequestId)
                .provider(provider)
                .providerOrderNumber(buildProviderOrderNumber(reservationRequestId))
                .idempotencyKey(idempotencyKey)
                .currency(currency)
                .amount(dueNowAmount)
                .status("CREATED")
                .updatedAt(OffsetDateTime.now())
                .build();
        paymentIntent = paymentIntentRepo.save(paymentIntent);

        PaymentProviderInitResult initResult;
        try {
            initResult = providerClient.initiate(paymentIntent, request == null ? new PaymentInitiateRequest() : request);
        } catch (Exception ex) {
            paymentIntent.setStatus("FAILED");
            paymentIntent.setErrorMessage(ex.getMessage());
            paymentIntent.setUpdatedAt(OffsetDateTime.now());
            paymentIntentRepo.save(paymentIntent);
            throw ex;
        }

        paymentIntent.setProviderPaymentId(initResult.providerPaymentId());
        paymentIntent.setClientSecret(initResult.clientSecret());
        paymentIntent.setStatus("PENDING_CUSTOMER");
        paymentIntent.setUpdatedAt(OffsetDateTime.now());
        paymentIntentRepo.save(paymentIntent);

        if (reservationRequest.getStatus() == ReservationRequest.Status.DRAFT) {
            reservationRequest.setStatus(ReservationRequest.Status.PENDING_PAYMENT);
            requestRepo.save(reservationRequest);
        }

        return toInitiateResponse(paymentIntent);
    }

    @Transactional
    public void processMonriWebhook(Long tenantId, JsonNode payload, String callbackToken) {
        PaymentProviderClient providerClient = providerClients.get("MONRI");
        if (providerClient == null) {
            throw new IllegalStateException("Monri provider is not configured");
        }
        PaymentProviderWebhookResult webhook = providerClient.parseWebhook(payload);

        if (StringUtils.hasText(webhook.eventId())) {
            Optional<PaymentEvent> existing = paymentEventRepo.findByProviderAndProviderEventId("MONRI", webhook.eventId());
            if (existing.isPresent()) {
                return;
            }
        }

        PaymentIntent paymentIntent = resolveIntentForWebhook(webhook)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found for webhook payload"));
        if (!Objects.equals(paymentIntent.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("Webhook tenant does not match payment intent tenant");
        }

        String configuredToken = monriTenantConfigResolver.resolve(tenantId).callbackAuthToken();
        if (StringUtils.hasText(configuredToken) && !Objects.equals(configuredToken, callbackToken)) {
            throw new IllegalArgumentException("Invalid Monri callback token");
        }

        paymentEventRepo.save(PaymentEvent.builder()
                .paymentIntentId(paymentIntent.getId())
                .provider("MONRI")
                .eventType(StringUtils.hasText(webhook.eventType()) ? webhook.eventType() : "unknown")
                .providerEventId(webhook.eventId())
                .payload(payload.toString())
                .build());

        if (StringUtils.hasText(webhook.providerPaymentId()) && !StringUtils.hasText(paymentIntent.getProviderPaymentId())) {
            paymentIntent.setProviderPaymentId(webhook.providerPaymentId());
        }

        String normalized = normalizeProviderStatus(webhook.paymentStatus());
        if ("PAID".equals(normalized)) {
            if (!"PAID".equalsIgnoreCase(paymentIntent.getStatus())) {
                paymentIntent.setStatus("PAID");
                paymentIntent.setCompletedAt(OffsetDateTime.now());
                paymentIntent.setUpdatedAt(OffsetDateTime.now());
                paymentIntentRepo.save(paymentIntent);
                reservationService.finalizeRequest(paymentIntent.getReservationRequestId());
                invoiceService.createDepositInvoiceForPaymentIntent(paymentIntent);
            }
            return;
        }
        if ("FAILED".equals(normalized)) {
            paymentIntent.setStatus("FAILED");
            paymentIntent.setUpdatedAt(OffsetDateTime.now());
            paymentIntentRepo.save(paymentIntent);
            return;
        }

        paymentIntent.setStatus("PENDING_CUSTOMER");
        paymentIntent.setUpdatedAt(OffsetDateTime.now());
        paymentIntentRepo.save(paymentIntent);
    }

    private Optional<PaymentIntent> resolveIntentForWebhook(PaymentProviderWebhookResult webhook) {
        if (StringUtils.hasText(webhook.orderNumber())) {
            Optional<PaymentIntent> byOrder = paymentIntentRepo.findByProviderAndProviderOrderNumber("MONRI", webhook.orderNumber());
            if (byOrder.isPresent()) {
                return byOrder;
            }
        }
        if (StringUtils.hasText(webhook.providerPaymentId())) {
            return paymentIntentRepo.findByProviderAndProviderPaymentId("MONRI", webhook.providerPaymentId());
        }
        return Optional.empty();
    }

    private String normalizeProvider(String provider) {
        String resolved = StringUtils.hasText(provider) ? provider : "MONRI";
        return resolved.trim().toUpperCase(Locale.ROOT);
    }

    private String buildProviderOrderNumber(Long reservationRequestId) {
        return "RR-" + reservationRequestId + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String resolveCurrency(List<Reservation> reservations) {
        String currency = reservations.stream()
                .map(Reservation::getCurrency)
                .filter(StringUtils::hasText)
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .findFirst()
                .orElse(null);
        if (!StringUtils.hasText(currency)) {
            throw new IllegalStateException("Reservation currency is required for payment");
        }
        boolean mismatch = reservations.stream()
                .map(Reservation::getCurrency)
                .filter(StringUtils::hasText)
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .anyMatch(v -> !v.equals(currency));
        if (mismatch) {
            throw new IllegalStateException("Mixed currency reservations in one request are not supported");
        }
        return currency;
    }

    private BigDecimal calculateDueNow(Long tenantId, List<Reservation> reservations) {
        List<DepositPolicy> tenantPolicies = depositPolicyRepo.findByTenantId(tenantId).stream()
                .filter(p -> Boolean.TRUE.equals(p.getActive()))
                .filter(this::isEffectiveNow)
                .sorted(Comparator.comparing(DepositPolicy::getPriority, Comparator.nullsLast(Comparator.naturalOrder())).reversed()
                        .thenComparing(DepositPolicy::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        BigDecimal due = BigDecimal.ZERO;
        for (Reservation reservation : reservations) {
            BigDecimal lineTotal = reservation.getGrossAmount();
            if (lineTotal == null && reservation.getUnitPrice() != null && reservation.getQty() != null) {
                lineTotal = reservation.getUnitPrice().multiply(BigDecimal.valueOf(reservation.getQty()));
            }
            if (lineTotal == null) {
                lineTotal = BigDecimal.ZERO;
            }
            DepositPolicy policy = resolvePolicyForReservation(tenantPolicies, reservation.getProductId());
            due = due.add(calculateLineDue(lineTotal, policy));
        }
        return due.setScale(2, RoundingMode.HALF_UP);
    }

    private DepositPolicy resolvePolicyForReservation(List<DepositPolicy> policies, Long productId) {
        if (productId != null) {
            Optional<DepositPolicy> productPolicy = policies.stream()
                    .filter(p -> "PRODUCT".equalsIgnoreCase(p.getScopeType()))
                    .filter(p -> Objects.equals(productId, p.getScopeId()))
                    .findFirst();
            if (productPolicy.isPresent()) {
                return productPolicy.get();
            }
        }
        return policies.stream()
                .filter(p -> "TENANT".equalsIgnoreCase(p.getScopeType()))
                .findFirst()
                .orElse(null);
    }

    private BigDecimal calculateLineDue(BigDecimal lineTotal, DepositPolicy policy) {
        if (policy == null) {
            return lineTotal;
        }
        String type = policy.getDepositType() == null ? "" : policy.getDepositType().toUpperCase(Locale.ROOT);
        if ("FULL".equals(type)) {
            return lineTotal;
        }
        if ("PERCENT".equals(type)) {
            BigDecimal percent = policy.getDepositValue() == null ? BigDecimal.ZERO : policy.getDepositValue();
            return lineTotal.multiply(percent).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        }
        if ("FIXED".equals(type)) {
            BigDecimal fixed = policy.getDepositValue() == null ? BigDecimal.ZERO : policy.getDepositValue();
            return fixed.min(lineTotal);
        }
        return lineTotal;
    }

    private BigDecimal reservationTotal(Reservation reservation) {
        BigDecimal lineTotal = reservation.getGrossAmount();
        if (lineTotal == null && reservation.getUnitPrice() != null && reservation.getQty() != null) {
            lineTotal = reservation.getUnitPrice().multiply(BigDecimal.valueOf(reservation.getQty()));
        }
        if (lineTotal == null) {
            lineTotal = BigDecimal.ZERO;
        }
        return lineTotal;
    }

    private boolean isEffectiveNow(DepositPolicy policy) {
        OffsetDateTime now = OffsetDateTime.now();
        if (policy.getEffectiveFrom() != null && policy.getEffectiveFrom().isAfter(now)) {
            return false;
        }
        if (policy.getEffectiveTo() != null && policy.getEffectiveTo().isBefore(now)) {
            return false;
        }
        return true;
    }

    private String normalizeProviderStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "PENDING";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("APPROV") || normalized.contains("CAPTUR") || normalized.contains("SUCCESS")) {
            return "PAID";
        }
        if (normalized.contains("DECLIN") || normalized.contains("FAIL") || normalized.contains("CANCEL") || normalized.contains("ERROR")) {
            return "FAILED";
        }
        return "PENDING";
    }

    private PaymentInitiateResponse toInitiateResponse(PaymentIntent intent) {
        return PaymentInitiateResponse.builder()
                .paymentIntentId(intent.getId())
                .provider(intent.getProvider())
                .status(intent.getStatus())
                .orderNumber(intent.getProviderOrderNumber())
                .providerPaymentId(intent.getProviderPaymentId())
                .clientSecret(intent.getClientSecret())
                .amount(intent.getAmount())
                .currency(intent.getCurrency())
                .build();
    }

    public record RequestPaymentSummary(
            BigDecimal totalAmount,
            BigDecimal dueNowAmount,
            BigDecimal paidAmount,
            BigDecimal remainingAmount,
            String paymentStatus
    ) {
    }
}
