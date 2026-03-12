package com.stackwizard.booking_api.service.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.PaymentInitiateRequest;
import com.stackwizard.booking_api.model.PaymentIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MonriPaymentProviderClient implements PaymentProviderClient {
    private static final Logger log = LoggerFactory.getLogger(MonriPaymentProviderClient.class);
    private static final String PROVIDER = "MONRI";

    private final MonriTenantConfigResolver configResolver;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<Long, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public MonriPaymentProviderClient(MonriTenantConfigResolver configResolver) {
        this.configResolver = configResolver;
        this.restClient = RestClient.builder().build();
    }

    @Override
    public String providerCode() {
        return PROVIDER;
    }

    @Override
    public PaymentProviderInitResult initiate(PaymentIntent paymentIntent, PaymentInitiateRequest request) {
        if (request == null) {
            request = new PaymentInitiateRequest();
        }
        MonriTenantConfigResolver.MonriResolvedConfig monri = configResolver.resolve(paymentIntent.getTenantId());

        String accessToken = getAccessToken(paymentIntent.getTenantId(), monri);
        JsonNode response = createPayment(monri, accessToken, paymentIntent, request);
        String providerPaymentId = firstText(response, "id", "payment_id", "uuid");
        String clientSecret = firstText(response, "client_secret", "payment_client_secret");
        String providerStatus = firstText(response, "status", "payment_status", "transaction_status");
        return new PaymentProviderInitResult(providerPaymentId, clientSecret, providerStatus);
    }

    @Override
    public PaymentProviderWebhookResult parseWebhook(String payload) {
        JsonNode json = parseJson(payload, "Monri webhook");
        JsonNode payloadNode = json.path("payload");
        JsonNode data = payloadNode.isObject() ? payloadNode : json;

        String eventType = firstText(json, "event", "event_type", "type", "status");
        if (!StringUtils.hasText(eventType)) {
            eventType = firstText(data, "event", "event_type", "type", "status");
        }

        String providerPaymentId = firstText(
                data,
                "id",
                "payment_id",
                "transaction_uuid",
                "uuid"
        );
        String orderNumber = firstText(
                data,
                "order_number",
                "order_info.order_number",
                "order_info"
        );
        String status = firstText(data, "status", "payment_status", "transaction_status");
        if (!StringUtils.hasText(status)) {
            // Monri webhook event strings often contain terminal state (approved/declined).
            status = eventType;
        }
        String eventId = firstText(json, "event_id", "id", "webhook_id");
        if (!StringUtils.hasText(eventId)) {
            eventId = buildSyntheticEventId(eventType, providerPaymentId, orderNumber);
        }
        return new PaymentProviderWebhookResult(eventId, eventType, orderNumber, providerPaymentId, status);
    }

    private String buildSyntheticEventId(String eventType, String providerPaymentId, String orderNumber) {
        if (!StringUtils.hasText(eventType) && !StringUtils.hasText(providerPaymentId) && !StringUtils.hasText(orderNumber)) {
            return null;
        }
        return String.format(
                "%s|%s|%s",
                defaultIfBlank(eventType, "unknown-event"),
                defaultIfBlank(providerPaymentId, "unknown-payment"),
                defaultIfBlank(orderNumber, "unknown-order")
        );
    }

    private String getAccessToken(Long tenantId, MonriTenantConfigResolver.MonriResolvedConfig monri) {
        CachedToken cached = tokenCache.get(tenantId);
        if (cached != null && cached.expiresAt().isAfter(OffsetDateTime.now().plusSeconds(30))) {
            return cached.token();
        }

        synchronized (tokenCache) {
            cached = tokenCache.get(tenantId);
            if (cached != null && cached.expiresAt().isAfter(OffsetDateTime.now().plusSeconds(30))) {
                return cached.token();
            }
            OAuthToken token = fetchAccessToken(monri);
            OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(Math.max(60, token.expiresInSeconds() - 30));
            tokenCache.put(tenantId, new CachedToken(token.accessToken(), expiresAt));
            return token.accessToken();
        }
    }

    private OAuthToken fetchAccessToken(MonriTenantConfigResolver.MonriResolvedConfig monri) {
        Map<String, Object> body = Map.of(
                "client_id", monri.clientId(),
                "client_secret", monri.clientSecret(),
                "scopes", List.of("payments"),
                "grant_type", "client_credentials"
        );
        log.info("Monri OAuth request: url={}, body={{client_id={}, client_secret={}, scopes=[payments], grant_type=client_credentials}}",
                normalizeUrl(monri.baseUrl(), monri.oauthPath()),
                mask(monri.clientId()),
                mask(monri.clientSecret()));
        try {
            String raw = restClient.post()
                    .uri(normalizeUrl(monri.baseUrl(), monri.oauthPath()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode json = parseJson(raw, "Monri OAuth");
            if (json == null || !StringUtils.hasText(json.path("access_token").asText(null))) {
                throw new IllegalStateException("Monri OAuth response missing access_token");
            }
            String accessToken = json.path("access_token").asText();
            long expiresIn = json.path("expires_in").asLong(3600);
            log.info("Monri OAuth response: status={}, expires_in={}, token={}",
                    json.path("status").asText("unknown"), expiresIn, mask(accessToken));
            return new OAuthToken(accessToken, expiresIn);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Failed to obtain Monri OAuth token", ex);
        }
    }

    private JsonNode createPayment(MonriTenantConfigResolver.MonriResolvedConfig monri, String accessToken, PaymentIntent paymentIntent, PaymentInitiateRequest request) {
        String transactionType = defaultIfBlank(request.getTransactionType(), "purchase");
        String orderInfo = defaultIfBlank(request.getOrderInfo(), "Reservation request #" + paymentIntent.getReservationRequestId());

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("authenticity_token", monri.authenticityToken() == null ? "" : monri.authenticityToken());
        body.put("currency", paymentIntent.getCurrency());
        body.put("order_number", paymentIntent.getProviderOrderNumber());
        body.put("order_info", orderInfo);
        body.put("transaction_type", transactionType);
        body.put("amount", toMinorUnits(paymentIntent.getAmount()));
        if (StringUtils.hasText(request.getLanguage())) {
            body.put("language", request.getLanguage());
        }
        if (StringUtils.hasText(request.getSuccessUrl()) || StringUtils.hasText(request.getCancelUrl())) {
            body.put("redirect_urls", Map.of(
                    "success", defaultIfBlank(request.getSuccessUrl(), ""),
                    "cancel", defaultIfBlank(request.getCancelUrl(), "")
            ));
        }
        if (StringUtils.hasText(request.getCallbackUrl())) {
            body.put("callback_url", request.getCallbackUrl());
        }
        log.info("Monri payment/new request: order={}, tenant={}, url={}, callbackUrl={}, successUrl={}, cancelUrl={}, amountMinor={}, currency={}, authenticityToken={}",
                paymentIntent.getProviderOrderNumber(),
                paymentIntent.getTenantId(),
                normalizeUrl(monri.baseUrl(), monri.requestPath()),
                defaultIfBlank(request.getCallbackUrl(), ""),
                defaultIfBlank(request.getSuccessUrl(), ""),
                defaultIfBlank(request.getCancelUrl(), ""),
                toMinorUnits(paymentIntent.getAmount()),
                paymentIntent.getCurrency(),
                mask(monri.authenticityToken()));

        try {
            String raw = restClient.post()
                    .uri(normalizeUrl(monri.baseUrl(), monri.requestPath()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode response = parseJson(raw, "Monri payment/new");
            log.info("Monri payment/new response for order {}: {}", paymentIntent.getProviderOrderNumber(),
                    response != null ? response.toString() : "<null>");
            return response;
        } catch (HttpClientErrorException ex) {
            log.error("Monri payment init failed for order {}. Status={} body={}",
                    paymentIntent.getProviderOrderNumber(),
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString(), ex);
            throw new IllegalStateException("Failed to initialize Monri payment: " + ex.getResponseBodyAsString(), ex);
        } catch (RestClientException ex) {
            log.error("Monri payment init failed for order {}", paymentIntent.getProviderOrderNumber(), ex);
            throw new IllegalStateException("Failed to initialize Monri payment", ex);
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String normalizeUrl(String base, String path) {
        String b = base == null ? "" : base.trim();
        String p = path == null ? "" : path.trim();
        if (b.endsWith("/") && p.startsWith("/")) {
            return b.substring(0, b.length() - 1) + p;
        }
        if (!b.endsWith("/") && !p.startsWith("/")) {
            return b + "/" + p;
        }
        return b + p;
    }

    private long toMinorUnits(java.math.BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private String firstText(JsonNode root, String... paths) {
        if (root == null) {
            return null;
        }
        for (String path : paths) {
            JsonNode node = findPath(root, path);
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                String value = node.asText();
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private JsonNode findPath(JsonNode root, String path) {
        String[] parts = path.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null) {
                return null;
            }
            current = current.path(part);
        }
        return current;
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return "<empty>";
        }
        String v = value.trim();
        if (v.length() <= 8) {
            return "****";
        }
        return v.substring(0, 4) + "..." + v.substring(v.length() - 4);
    }

    private JsonNode parseJson(String raw, String operation) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException(operation + " response is empty");
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(operation + " returned invalid JSON: " + raw, ex);
        }
    }

    private record CachedToken(String token, OffsetDateTime expiresAt) {}
    private record OAuthToken(String accessToken, long expiresInSeconds) {}
}
