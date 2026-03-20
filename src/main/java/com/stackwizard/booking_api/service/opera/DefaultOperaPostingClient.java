package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class DefaultOperaPostingClient implements OperaPostingClient {
    private static final String CHARGES_AND_PAYMENTS_PATH = "/csh/v1/hotels/{hotelCode}/reservations/{reservationId}/chargesAndPayments";
    private static final String OAUTH_SCOPE = "urn:opc:hgbu:ws:__myscopes__";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultOperaPostingClient() {
        this.restClient = RestClient.builder().build();
    }

    @Override
    public JsonNode postChargesAndPayments(OperaTenantConfigResolver.OperaResolvedConfig config,
                                           String hotelCode,
                                           Long reservationId,
                                           JsonNode payload) {
        String requestUrl = buildChargesAndPaymentsUrl(config.baseUrl(), hotelCode, reservationId);
        String authorization = resolveAuthorization(config);
        String requestBody = payload == null ? "{}" : payload.toString();
        try {
            String raw = restClient.post()
                    .uri(requestUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-app-key", config.appKey())
                    .header("x-hotelid", hotelCode)
                    .header("Authorization", authorization)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            return parseJson(raw);
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("Opera posting request failed: " + ex.getStatusCode().value()
                    + " " + sanitize(ex.getResponseBodyAsString()), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Opera posting request failed", ex);
        }
    }

    private String resolveAuthorization(OperaTenantConfigResolver.OperaResolvedConfig config) {
        if (StringUtils.hasText(config.accessToken())) {
            return normalizeAuthorization(config.accessToken());
        }
        return "Bearer " + fetchAccessToken(config);
    }

    private String fetchAccessToken(OperaTenantConfigResolver.OperaResolvedConfig config) {
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");
            body.add("scope", OAUTH_SCOPE);

            String raw = restClient.post()
                    .uri(normalizeUrl(config.baseUrl(), config.oauthPath()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("x-app-key", config.appKey())
                    .header("enterpriseId", config.enterpriseId())
                    .header("Authorization", basicAuthorization(config.clientId(), config.clientSecret()))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode json = parseJson(raw);
            String accessToken = json.path("access_token").asText(null);
            if (!StringUtils.hasText(accessToken)) {
                throw new IllegalStateException("Opera OAuth response missing access_token");
            }
            return accessToken.trim();
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("Opera OAuth login failed: " + ex.getStatusCode().value()
                    + " " + sanitize(ex.getResponseBodyAsString()), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Opera OAuth login failed", ex);
        }
    }

    private String basicAuthorization(String clientId, String clientSecret) {
        String value = (clientId == null ? "" : clientId) + ":" + (clientSecret == null ? "" : clientSecret);
        String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return objectMapper.createObjectNode().put("rawBody", raw);
        }
    }

    private String buildChargesAndPaymentsUrl(String baseUrl, String hotelCode, Long reservationId) {
        String resolvedPath = CHARGES_AND_PAYMENTS_PATH
                .replace("{hotelCode}", hotelCode)
                .replace("{reservationId}", reservationId != null ? reservationId.toString() : "");
        return normalizeUrl(baseUrl, resolvedPath);
    }

    private String normalizeUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        String resolvedPath = path == null ? "" : path.trim();
        if (resolvedPath.startsWith("http://") || resolvedPath.startsWith("https://")) {
            return resolvedPath;
        }
        if (base.endsWith("/") && resolvedPath.startsWith("/")) {
            return base.substring(0, base.length() - 1) + resolvedPath;
        }
        if (!base.endsWith("/") && !resolvedPath.startsWith("/")) {
            return base + "/" + resolvedPath;
        }
        return base + resolvedPath;
    }

    private String normalizeAuthorization(String accessToken) {
        String token = accessToken == null ? "" : accessToken.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return token;
        }
        return "Bearer " + token;
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "<empty>";
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}
