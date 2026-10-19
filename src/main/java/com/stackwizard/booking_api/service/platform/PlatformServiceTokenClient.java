package com.stackwizard.booking_api.service.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Client-credentials token for Platform {@code /api/v1/service/**} (scope {@code integrations:read}).
 * Used when booking-api needs OHIP credentials outside a user request (Opera setup/sync).
 */
@Component
public class PlatformServiceTokenClient {
    private static final String SCOPE = "integrations:read";
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final RestClient platform;
    private final String clientId;
    private final String clientSecret;

    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    public PlatformServiceTokenClient(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String platformBaseUrl,
            @Value("${booking.platform.sync-client-id:}") String clientId,
            @Value("${booking.platform.sync-client-secret:}") String clientSecret) {
        String base = platformBaseUrl.endsWith("/")
                ? platformBaseUrl.substring(0, platformBaseUrl.length() - 1)
                : platformBaseUrl;
        this.platform = RestClient.builder().baseUrl(base).build();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    public boolean isConfigured() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    public synchronized String accessToken() {
        if (!isConfigured()) {
            throw new IllegalStateException("Platform booking-sync client credentials are not configured");
        }
        if (cachedToken != null && Instant.now().isBefore(cachedUntil)) {
            return cachedToken;
        }
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        JsonNode body = platform.post()
                .uri("/oauth2/token")
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&scope=" + SCOPE)
                .retrieve()
                .body(JsonNode.class);
        if (body == null || body.path("access_token").asText("").isBlank()) {
            throw new IllegalStateException("Platform token response missing access_token");
        }
        cachedToken = body.path("access_token").asText();
        long expiresIn = body.path("expires_in").asLong(300);
        cachedUntil = Instant.now().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
        return cachedToken;
    }

    public synchronized void invalidate() {
        cachedToken = null;
        cachedUntil = Instant.EPOCH;
    }
}
