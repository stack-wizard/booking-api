package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.List;
import java.util.Map;

@Component
public class DefaultOperaPostingClient implements OperaPostingClient {
    private static final Logger log = LoggerFactory.getLogger(DefaultOperaPostingClient.class);
    private static final String CHARGES_AND_PAYMENTS_PATH = "/csh/v1/hotels/{hotelCode}/reservations/{reservationId}/chargesAndPayments";
    private static final String CHARGES_PATH = "/csh/v1/hotels/{hotelCode}/reservations/{reservationId}/charges";
    private static final String CREATE_RESERVATION_PATH = "/rsv/v1/hotels/{hotelCode}/reservations";
    private static final String RESERVATIONS_PATH = "/rsv/v1/hotels/{hotelCode}/reservations";
    private static final String CHECK_IN_PATH = "/fof/v1/hotels/{hotelCode}/reservations/{reservationId}/checkIns";
    private static final String PAYMENT_PATH = "/csh/v1/hotels/{hotelCode}/reservations/{reservationId}/payments";
    private static final String OAUTH_SCOPE = "urn:opc:hgbu:ws:__myscopes__";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultOperaPostingClient() {
        this.restClient = RestClient.builder().build();
    }

    @Override
    public JsonNode postChargesAndPayments(OperaTenantConfigResolver.OperaResolvedConfig config,
                                           String hotelCode,
                                           String chainCode,
                                           Long reservationId,
                                           JsonNode payload) {
        String requestUrl = buildChargesAndPaymentsUrl(config.baseUrl(), hotelCode, reservationId);
        String authorization = resolveAuthorization(config, chainCode);
        String requestBody = payload == null ? "{}" : payload.toString();
        return postJson("chargesAndPayments", config, chainCode, hotelCode, requestUrl, authorization, requestBody);
    }

    @Override
    public JsonNode postCharges(OperaTenantConfigResolver.OperaResolvedConfig config,
                                String hotelCode,
                                String chainCode,
                                Long reservationId,
                                JsonNode payload) {
        String requestUrl = buildReservationUrl(config.baseUrl(), CHARGES_PATH, hotelCode, reservationId);
        String authorization = resolveAuthorization(config, chainCode);
        String requestBody = payload == null ? "{}" : payload.toString();
        return postJson("charges", config, chainCode, hotelCode, requestUrl, authorization, requestBody);
    }

    @Override
    public JsonNode postCreateReservation(OperaTenantConfigResolver.OperaResolvedConfig config,
                                          String chainCode,
                                          String hotelCode,
                                          JsonNode body) {
        String path = CREATE_RESERVATION_PATH.replace("{hotelCode}", hotelCode);
        String requestUrl = normalizeUrl(config.baseUrl(), path);
        String authorization = resolveAuthorization(config, chainCode);
        String requestBody = body == null ? "{}" : body.toString();
        return postJson("createReservation", config, chainCode, hotelCode, requestUrl, authorization, requestBody);
    }

    @Override
    public JsonNode postCheckIn(OperaTenantConfigResolver.OperaResolvedConfig config,
                                String chainCode,
                                String hotelCode,
                                Long reservationId,
                                JsonNode body) {
        String path = CHECK_IN_PATH
                .replace("{hotelCode}", hotelCode)
                .replace("{reservationId}", reservationId != null ? reservationId.toString() : "");
        String requestUrl = normalizeUrl(config.baseUrl(), path);
        String authorization = resolveAuthorization(config, chainCode);
        String requestBody = body == null ? "{}" : body.toString();
        return postJson("checkIn", config, chainCode, hotelCode, requestUrl, authorization, requestBody);
    }

    @Override
    public JsonNode postPayment(OperaTenantConfigResolver.OperaResolvedConfig config,
                                String chainCode,
                                String hotelCode,
                                Long reservationId,
                                JsonNode body) {
        String path = PAYMENT_PATH
                .replace("{hotelCode}", hotelCode)
                .replace("{reservationId}", reservationId != null ? reservationId.toString() : "");
        String requestUrl = normalizeUrl(config.baseUrl(), path);
        String authorization = resolveAuthorization(config, chainCode);
        String requestBody = body == null ? "{}" : body.toString();
        return postJson("payment", config, chainCode, hotelCode, requestUrl, authorization, requestBody);
    }

    @Override
    public JsonNode getReservations(OperaTenantConfigResolver.OperaResolvedConfig config,
                                    String chainCode,
                                    String hotelCode,
                                    Map<String, List<String>> queryParams) {
        String requestUrl = appendQueryParams(
                normalizeUrl(config.baseUrl(), RESERVATIONS_PATH.replace("{hotelCode}", hotelCode)),
                queryParams);
        String authorization = resolveAuthorization(config, chainCode);
        try {
            String raw = restClient.get()
                    .uri(requestUrl)
                    .header("x-app-key", config.appKey())
                    .header("x-hotelid", hotelCode)
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(String.class);
            return parseJson(raw);
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException("Opera reservation lookup failed: " + ex.getStatusCode().value()
                    + " " + sanitize(ex.getResponseBodyAsString()), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Opera reservation lookup failed", ex);
        }
    }

    private JsonNode postJson(String operation,
                              OperaTenantConfigResolver.OperaResolvedConfig config,
                              String chainCode,
                              String hotelCode,
                              String requestUrl,
                              String authorization,
                              String requestBody) {
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
            log.error("Opera POST failed. operation={}, hotelCode={}, chainCode={}, url={}, status={}, requestBody={}, responseBody={}",
                    operation,
                    hotelCode,
                    safe(chainCode),
                    requestUrl,
                    ex.getStatusCode().value(),
                    safe(requestBody),
                    sanitize(ex.getResponseBodyAsString()),
                    ex);
            throw new IllegalStateException("Opera posting request failed: " + ex.getStatusCode().value()
                    + " " + sanitize(ex.getResponseBodyAsString()), ex);
        } catch (RestClientException ex) {
            log.error("Opera POST failed before response. operation={}, hotelCode={}, chainCode={}, url={}, requestBody={}",
                    operation,
                    hotelCode,
                    safe(chainCode),
                    requestUrl,
                    safe(requestBody),
                    ex);
            throw new IllegalStateException("Opera posting request failed", ex);
        }
    }

    private String resolveAuthorization(OperaTenantConfigResolver.OperaResolvedConfig config, String chainCode) {
        if (StringUtils.hasText(config.accessToken())) {
            return normalizeAuthorization(config.accessToken());
        }
        return "Bearer " + fetchAccessToken(config, chainCode);
    }

    private String fetchAccessToken(OperaTenantConfigResolver.OperaResolvedConfig config, String chainCode) {
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");
            body.add("scope", OAUTH_SCOPE);

            RestClient.RequestBodySpec request = restClient.post()
                    .uri(normalizeUrl(config.baseUrl(), config.oauthPath()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("x-app-key", config.appKey())
                    .header("enterpriseId", config.enterpriseId())
                    .header("Authorization", basicAuthorization(config.clientId(), config.clientSecret()));
            if (StringUtils.hasText(chainCode)) {
                request.header("ChainCode", chainCode.trim());
            }

            String raw = request.body(body).retrieve().body(String.class);
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
        return buildReservationUrl(baseUrl, CHARGES_AND_PAYMENTS_PATH, hotelCode, reservationId);
    }

    private String buildReservationUrl(String baseUrl, String templatePath, String hotelCode, Long reservationId) {
        String resolvedPath = templatePath
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

    private String safe(String value) {
        return StringUtils.hasText(value) ? value.trim() : "<empty>";
    }

    private String appendQueryParams(String requestUrl, Map<String, List<String>> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return requestUrl;
        }
        StringBuilder url = new StringBuilder(requestUrl);
        char separator = requestUrl.contains("?") ? '&' : '?';
        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value == null) {
                    continue;
                }
                url.append(separator)
                        .append(entry.getKey())
                        .append('=')
                        .append(value);
                separator = '&';
            }
        }
        return url.toString();
    }
}
