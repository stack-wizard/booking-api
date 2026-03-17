package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_PENDING_CUSTOMER = "PENDING_CUSTOMER";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_PAID = "PAID";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELED = "CANCELED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final int DEFAULT_INTENT_TTL_MINUTES = 15;
    private static final List<String> ACTIVE_INTENT_STATUSES = List.of(
            STATUS_CREATED, STATUS_PENDING_CUSTOMER, STATUS_PROCESSING
    );
    private static final List<String> EXPIRABLE_INTENT_STATUSES = List.of(
            STATUS_PENDING_CUSTOMER
    );
    private static final List<String> REUSABLE_INTENT_STATUSES = List.of(
            STATUS_PENDING_CUSTOMER, STATUS_PROCESSING
    );
    private static final String MONRI_CALLBACK_SCHEME = "WP3-callback";
    private static final Pattern HEX_512_PATTERN = Pattern.compile("^[0-9a-fA-F]{128}$");

    private final PaymentIntentRepository paymentIntentRepo;
    private final PaymentEventRepository paymentEventRepo;
    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final DepositPolicyRepository depositPolicyRepo;
    private final ReservationService reservationService;
    private final InvoiceService invoiceService;
    private final MonriTenantConfigResolver monriTenantConfigResolver;
    private final Environment environment;
    private final Map<String, PaymentProviderClient> providerClients;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentService(PaymentIntentRepository paymentIntentRepo,
                          PaymentEventRepository paymentEventRepo,
                          ReservationRequestRepository requestRepo,
                          ReservationRepository reservationRepo,
                          DepositPolicyRepository depositPolicyRepo,
                          ReservationService reservationService,
                          InvoiceService invoiceService,
                          MonriTenantConfigResolver monriTenantConfigResolver,
                          Environment environment,
                          List<PaymentProviderClient> providerClients) {
        this.paymentIntentRepo = paymentIntentRepo;
        this.paymentEventRepo = paymentEventRepo;
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.depositPolicyRepo = depositPolicyRepo;
        this.reservationService = reservationService;
        this.invoiceService = invoiceService;
        this.monriTenantConfigResolver = monriTenantConfigResolver;
        this.environment = environment;
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
                .filter(i -> STATUS_PAID.equalsIgnoreCase(i.getStatus()))
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

        OffsetDateTime now = OffsetDateTime.now();
        List<PaymentIntent> activeIntents = expireStaleActiveIntents(reservationRequestId, now);
        Optional<PaymentIntent> reusable = activeIntents.stream()
                .filter(intent -> REUSABLE_INTENT_STATUSES.contains(normalizeIntentStatus(intent.getStatus())))
                .filter(intent -> provider.equalsIgnoreCase(intent.getProvider()))
                .filter(intent -> sameAmount(intent.getAmount(), dueNowAmount))
                .filter(intent -> Objects.equals(intent.getCurrency(), currency))
                .findFirst();
        if (reusable.isPresent()) {
            return toInitiateResponse(reusable.get());
        }
        supersedeActiveIntents(activeIntents, now);

        PaymentIntent paymentIntent = PaymentIntent.builder()
                .tenantId(reservationRequest.getTenantId())
                .reservationRequestId(reservationRequestId)
                .provider(provider)
                .providerOrderNumber(buildProviderOrderNumber(reservationRequestId))
                .idempotencyKey(buildIdempotencyKey(reservationRequestId, provider))
                .currency(currency)
                .amount(dueNowAmount)
                .status(STATUS_CREATED)
                .expiresAt(now.plusMinutes(DEFAULT_INTENT_TTL_MINUTES))
                .updatedAt(now)
                .build();
        paymentIntent = paymentIntentRepo.save(paymentIntent);

        PaymentProviderInitResult initResult;
        try {
            initResult = providerClient.initiate(paymentIntent, request == null ? new PaymentInitiateRequest() : request);
        } catch (Exception ex) {
            paymentIntent.setStatus(STATUS_FAILED);
            paymentIntent.setErrorMessage(ex.getMessage());
            paymentIntent.setCompletedAt(OffsetDateTime.now());
            paymentIntent.setUpdatedAt(OffsetDateTime.now());
            paymentIntentRepo.save(paymentIntent);
            throw ex;
        }

        paymentIntent.setProviderPaymentId(initResult.providerPaymentId());
        paymentIntent.setClientSecret(initResult.clientSecret());
        paymentIntent.setStatus(STATUS_PENDING_CUSTOMER);
        paymentIntent.setUpdatedAt(OffsetDateTime.now());
        paymentIntentRepo.save(paymentIntent);

        if (reservationRequest.getStatus() == ReservationRequest.Status.DRAFT) {
            reservationRequest.setStatus(ReservationRequest.Status.PENDING_PAYMENT);
            requestRepo.save(reservationRequest);
        }

        return toInitiateResponse(paymentIntent);
    }

    @Transactional
    public void processMonriWebhook(Long tenantId, String payload, String callbackToken) {
        PaymentProviderClient providerClient = providerClients.get("MONRI");
        if (providerClient == null) {
            throw new IllegalStateException("Monri provider is not configured");
        }
        JsonNode payloadNode = parsePayloadNode(payload);
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
                .payload(payloadNode)
                .build());

        if (StringUtils.hasText(webhook.providerPaymentId()) && !StringUtils.hasText(paymentIntent.getProviderPaymentId())) {
            paymentIntent.setProviderPaymentId(webhook.providerPaymentId());
        }

        String normalized = normalizeProviderStatus(webhook.paymentStatus());
        applyMonriStatusTransition(paymentIntent, normalized);
    }

    @Transactional
    public void processMonriCallback(Long tenantId,
                                     String payload,
                                     String callbackToken,
                                     String authorization,
                                     String httpAuthorization) {
        MonriTenantConfigResolver.MonriResolvedConfig monriConfig = monriTenantConfigResolver.resolve(tenantId);
        if (isLocalProfileActive()) {
            log.warn("Skipping Monri callback authorization digest verification for tenant {} because local profile is active",
                    tenantId);
        } else {
            verifyMonriCallbackDigest(
                    tenantId,
                    monriConfig.callbackAuthToken(),
                    monriConfig.clientSecret(),
                    payload,
                    authorization,
                    httpAuthorization
            );
        }
        JsonNode payloadNode = parsePayloadNode(payload);
        JsonNode data = payloadNode.path("payload").isObject() ? payloadNode.path("payload") : payloadNode;

        String orderNumber = firstText(data, "order_number", "order_info.order_number", "order_info");
        String providerPaymentId = firstText(data, "id", "payment_id", "transaction_uuid", "uuid");
        PaymentIntent paymentIntent = resolveIntentForMonri(orderNumber, providerPaymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found for callback payload"));
        if (!Objects.equals(paymentIntent.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("Callback tenant does not match payment intent tenant");
        }

        String configuredToken = monriConfig.callbackAuthToken();
        if (StringUtils.hasText(configuredToken) && !Objects.equals(configuredToken, callbackToken)) {
            throw new IllegalArgumentException("Invalid Monri callback token");
        }

        String providerEventId = buildMonriCallbackEventId(orderNumber, providerPaymentId);
        if (StringUtils.hasText(providerEventId)) {
            Optional<PaymentEvent> existing = paymentEventRepo.findByProviderAndProviderEventId("MONRI", providerEventId);
            if (existing.isPresent()) {
                return;
            }
        }

        paymentEventRepo.save(PaymentEvent.builder()
                .paymentIntentId(paymentIntent.getId())
                .provider("MONRI")
                .eventType("callback:approved")
                .providerEventId(providerEventId)
                .payload(payloadNode)
                .build());

        if (StringUtils.hasText(providerPaymentId) && !StringUtils.hasText(paymentIntent.getProviderPaymentId())) {
            paymentIntent.setProviderPaymentId(providerPaymentId);
        }

        applyMonriStatusTransition(paymentIntent, STATUS_PAID);
    }

    private boolean isLocalProfileActive() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "local".equalsIgnoreCase(profile));
    }

    private void verifyMonriCallbackDigest(Long tenantId,
                                           String callbackAuthToken,
                                           String clientId,
                                           String payload,
                                           String authorization,
                                           String httpAuthorization) {
        String callbackKey = normalizeSecret(callbackAuthToken);
        String clientKey = normalizeSecret(clientId);
        if (!StringUtils.hasText(callbackKey) && !StringUtils.hasText(clientKey)) {
            throw new IllegalArgumentException("Monri digest key is missing for callback verification");
        }
        String authorizationHeader = StringUtils.hasText(authorization) ? authorization : httpAuthorization;
        if (!StringUtils.hasText(authorizationHeader)) {
            throw new IllegalArgumentException("Missing Monri authorization header");
        }

        String providedDigest = extractMonriDigest(authorizationHeader);
        String normalizedProvidedDigest = providedDigest.toLowerCase(Locale.ROOT);
        if (StringUtils.hasText(callbackKey) && digestMatches(normalizedProvidedDigest, sha512Hex(callbackKey + payload))) {
            return;
        }
        if (StringUtils.hasText(clientKey) && digestMatches(normalizedProvidedDigest, sha512Hex(clientKey + payload))) {
            return;
        }
        log.warn("Monri callback digest mismatch for tenant {}. payloadLength={}, hasCallbackKey={}, hasClientId={}",
                tenantId, payload.length(), StringUtils.hasText(callbackKey), StringUtils.hasText(clientKey));
        throw new IllegalArgumentException("Invalid Monri callback digest");
    }

    private boolean digestMatches(String providedDigest, String expectedDigest) {
        byte[] expectedBytes = expectedDigest.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = providedDigest.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private String normalizeSecret(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String extractMonriDigest(String authorizationHeader) {
        String trimmed = authorizationHeader.trim();
        int separatorIndex = trimmed.indexOf(' ');
        if (separatorIndex <= 0) {
            throw new IllegalArgumentException("Invalid Monri authorization header format");
        }
        String scheme = trimmed.substring(0, separatorIndex).trim();
        String digest = trimmed.substring(separatorIndex + 1).trim();
        if (!MONRI_CALLBACK_SCHEME.equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Invalid Monri authorization scheme");
        }
        if (!HEX_512_PATTERN.matcher(digest).matches()) {
            throw new IllegalArgumentException("Invalid Monri digest format");
        }
        return digest;
    }

    private String sha512Hex(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-512")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-512 algorithm is not available", ex);
        }
    }

    private JsonNode parsePayloadNode(String payload) {
        if (!StringUtils.hasText(payload)) {
            throw new IllegalArgumentException("Webhook payload is empty");
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Webhook payload is not valid JSON");
        }
    }

    @Transactional
    public PaymentIntent markIntentProcessing(Long paymentIntentId) {
        PaymentIntent intent = paymentIntentRepo.findById(paymentIntentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found"));
        String status = normalizeIntentStatus(intent.getStatus());
        if (isTerminalStatus(status)) {
            return intent;
        }
        if (!STATUS_PENDING_CUSTOMER.equals(status)) {
            throw new IllegalStateException("Only PENDING_CUSTOMER intent can transition to PROCESSING");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (isExpired(intent, now)) {
            intent.setStatus(STATUS_EXPIRED);
            intent.setCompletedAt(now);
            intent.setUpdatedAt(now);
            if (!StringUtils.hasText(intent.getErrorMessage())) {
                intent.setErrorMessage("Payment intent expired");
            }
            paymentIntentRepo.save(intent);
            throw new IllegalStateException("Payment intent is expired");
        }
        intent.setStatus(STATUS_PROCESSING);
        intent.setUpdatedAt(now);
        return paymentIntentRepo.save(intent);
    }

    @Scheduled(fixedDelayString = "${payments.intent-expiry-scan-ms:60000}")
    @Transactional
    public void expireStalePaymentIntents() {
        OffsetDateTime now = OffsetDateTime.now();
        List<PaymentIntent> stale = paymentIntentRepo.findByStatusInAndExpiresAtBefore(EXPIRABLE_INTENT_STATUSES, now);
        if (stale.isEmpty()) {
            return;
        }
        for (PaymentIntent intent : stale) {
            intent.setStatus(STATUS_EXPIRED);
            intent.setCompletedAt(now);
            intent.setUpdatedAt(now);
            if (!StringUtils.hasText(intent.getErrorMessage())) {
                intent.setErrorMessage("Payment intent expired");
            }
        }
        paymentIntentRepo.saveAll(stale);
    }

    private Optional<PaymentIntent> resolveIntentForWebhook(PaymentProviderWebhookResult webhook) {
        return resolveIntentForMonri(webhook.orderNumber(), webhook.providerPaymentId());
    }

    private Optional<PaymentIntent> resolveIntentForMonri(String orderNumber, String providerPaymentId) {
        if (StringUtils.hasText(orderNumber)) {
            Optional<PaymentIntent> byOrder = paymentIntentRepo.findByProviderAndProviderOrderNumber("MONRI", orderNumber);
            if (byOrder.isPresent()) {
                return byOrder;
            }
        }
        if (StringUtils.hasText(providerPaymentId)) {
            return paymentIntentRepo.findByProviderAndProviderPaymentId("MONRI", providerPaymentId);
        }
        return Optional.empty();
    }

    private void applyMonriStatusTransition(PaymentIntent paymentIntent, String normalizedStatus) {
        String previousStatus = normalizeIntentStatus(paymentIntent.getStatus());
        OffsetDateTime now = OffsetDateTime.now();
        if (STATUS_PAID.equals(normalizedStatus)) {
            if (!STATUS_PAID.equals(previousStatus)) {
                paymentIntent.setStatus(STATUS_PAID);
                paymentIntent.setCompletedAt(now);
                paymentIntent.setUpdatedAt(now);
                paymentIntentRepo.save(paymentIntent);

                // Payment for superseded/expired/canceled intents is stored, but finalized manually.
                if (!STATUS_SUPERSEDED.equals(previousStatus)
                        && !STATUS_EXPIRED.equals(previousStatus)
                        && !STATUS_CANCELED.equals(previousStatus)) {
                    reservationService.finalizeRequest(paymentIntent.getReservationRequestId());
                    invoiceService.createDepositInvoiceForPaymentIntent(paymentIntent);
                }
            }
            return;
        }
        if (STATUS_FAILED.equals(normalizedStatus)) {
            if (!STATUS_PAID.equals(previousStatus)) {
                paymentIntent.setStatus(STATUS_FAILED);
                paymentIntent.setCompletedAt(now);
                paymentIntent.setUpdatedAt(now);
                paymentIntentRepo.save(paymentIntent);
            }
            return;
        }
        if (STATUS_CANCELED.equals(normalizedStatus)) {
            if (!STATUS_PAID.equals(previousStatus)) {
                paymentIntent.setStatus(STATUS_CANCELED);
                paymentIntent.setCompletedAt(now);
                paymentIntent.setUpdatedAt(now);
                paymentIntentRepo.save(paymentIntent);
            }
            return;
        }

        if (!isTerminalStatus(previousStatus)) {
            paymentIntent.setStatus(STATUS_PROCESSING);
            paymentIntent.setUpdatedAt(now);
            paymentIntentRepo.save(paymentIntent);
        }
    }

    private String buildMonriCallbackEventId(String orderNumber, String providerPaymentId) {
        if (!StringUtils.hasText(orderNumber) && !StringUtils.hasText(providerPaymentId)) {
            return null;
        }
        String order = StringUtils.hasText(orderNumber) ? orderNumber.trim() : "unknown-order";
        String payment = StringUtils.hasText(providerPaymentId) ? providerPaymentId.trim() : "unknown-payment";
        return "callback|" + order + "|" + payment;
    }

    private String firstText(JsonNode root, String... paths) {
        if (root == null) {
            return null;
        }
        for (String path : paths) {
            JsonNode current = root;
            for (String part : path.split("\\.")) {
                if (current == null) {
                    break;
                }
                current = current.path(part);
            }
            if (current != null && !current.isMissingNode() && !current.isNull()) {
                String value = current.asText();
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private List<PaymentIntent> expireStaleActiveIntents(Long reservationRequestId, OffsetDateTime now) {
        List<PaymentIntent> active = paymentIntentRepo.findByReservationRequestIdAndStatusInOrderByCreatedAtDesc(
                reservationRequestId,
                ACTIVE_INTENT_STATUSES
        );
        boolean changed = false;
        for (PaymentIntent intent : active) {
            if (STATUS_PENDING_CUSTOMER.equals(normalizeIntentStatus(intent.getStatus())) && isExpired(intent, now)) {
                intent.setStatus(STATUS_EXPIRED);
                intent.setCompletedAt(now);
                intent.setUpdatedAt(now);
                if (!StringUtils.hasText(intent.getErrorMessage())) {
                    intent.setErrorMessage("Payment intent expired");
                }
                changed = true;
            }
        }
        if (changed) {
            paymentIntentRepo.saveAll(active);
        }
        return active.stream()
                .filter(intent -> ACTIVE_INTENT_STATUSES.contains(normalizeIntentStatus(intent.getStatus())))
                .toList();
    }

    private void supersedeActiveIntents(List<PaymentIntent> activeIntents, OffsetDateTime now) {
        if (activeIntents.isEmpty()) {
            return;
        }
        for (PaymentIntent intent : activeIntents) {
            intent.setStatus(STATUS_SUPERSEDED);
            intent.setCompletedAt(now);
            intent.setUpdatedAt(now);
            if (!StringUtils.hasText(intent.getErrorMessage())) {
                intent.setErrorMessage("Superseded by a newer payment intent");
            }
        }
        paymentIntentRepo.saveAll(activeIntents);
    }

    private boolean isExpired(PaymentIntent intent, OffsetDateTime now) {
        return intent.getExpiresAt() != null && !intent.getExpiresAt().isAfter(now);
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return false;
        }
        return left.compareTo(right) == 0;
    }

    private String buildIdempotencyKey(Long reservationRequestId, String provider) {
        return "reservation-request:" + reservationRequestId + ":" + provider + ":" + UUID.randomUUID();
    }

    private String normalizeIntentStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }

    private boolean isTerminalStatus(String status) {
        String normalized = normalizeIntentStatus(status);
        return STATUS_PAID.equals(normalized)
                || STATUS_FAILED.equals(normalized)
                || STATUS_CANCELED.equals(normalized)
                || STATUS_EXPIRED.equals(normalized)
                || STATUS_SUPERSEDED.equals(normalized);
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
            return STATUS_PAID;
        }
        if (normalized.contains("CANCEL")) {
            return STATUS_CANCELED;
        }
        if (normalized.contains("DECLIN") || normalized.contains("FAIL") || normalized.contains("ERROR")) {
            return STATUS_FAILED;
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
                .expiresAt(intent.getExpiresAt())
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
