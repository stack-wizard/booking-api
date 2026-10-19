package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    /** Renew this long before OPERA says the token expires. */
    private static final Duration TOKEN_RENEW_MARGIN = Duration.ofSeconds(60);
    private static final Duration TOKEN_DEFAULT_LIFETIME = Duration.ofMinutes(5);

    /** Everything the OAuth login depends on; a changed secret or chain gets its own token. */
    private record TokenKey(String baseUrl, String oauthPath, String appKey, String clientId, String clientSecret,
                            String enterpriseId, String chainCode) {
    }

    private record CachedToken(String value, Instant renewAt) {
    }

    private final RestClient restClient;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<TokenKey, CachedToken> tokens = new ConcurrentHashMap<>();

    public DefaultOperaPostingClient() {
        this(RestClient.builder(), Clock.systemUTC());
    }

    DefaultOperaPostingClient(RestClient.Builder restClientBuilder, Clock clock) {
        this.restClient = restClientBuilder.build();
        this.clock = clock;
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
        return getJson("Opera reservation lookup", config, chainCode, hotelCode, requestUrl);
    }

    @Override
    public JsonNode get(OperaTenantConfigResolver.OperaResolvedConfig config,
                        String chainCode,
                        String hotelCode,
                        String path,
                        Map<String, List<String>> queryParams) {
        String requestUrl = appendQueryParams(normalizeUrl(config.baseUrl(), path), queryParams);
        return getJson("Opera request " + path, config, chainCode, hotelCode, requestUrl);
    }

    @Override
    public WriteResult post(OperaTenantConfigResolver.OperaResolvedConfig config,
                            String chainCode,
                            String hotelCode,
                            String path,
                            JsonNode body) {
        String requestUrl = normalizeUrl(config.baseUrl(), path);
        String authorization = resolveAuthorization(config, chainCode);
        String requestBody = body == null ? "{}" : body.toString();
        try {
            var response = restClient.post()
                    .uri(requestUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-app-key", config.appKey())
                    .header("x-hotelid", hotelCode)
                    .header("Authorization", authorization)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);
            return new WriteResult(parseJson(response.getBody()), firstHeader(response.getHeaders(), "Location"));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                evictToken(config, chainCode);
            }
            log.error("Opera POST failed. operation={}, hotelCode={}, url={}, status={}, requestBody={}, responseBody={}",
                    path, hotelCode, requestUrl, ex.getStatusCode().value(), safe(requestBody),
                    sanitize(ex.getResponseBodyAsString()), ex);
            throw new IllegalStateException("Opera posting request failed: " + ex.getStatusCode().value()
                    + " " + sanitize(ex.getResponseBodyAsString()), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Opera posting request failed", ex);
        }
    }

    @Override
    public JsonNode put(OperaTenantConfigResolver.OperaResolvedConfig config,
                        String chainCode,
                        String hotelCode,
                        String path,
                        JsonNode body) {
        String requestUrl = normalizeUrl(config.baseUrl(), path);
        String authorization = resolveAuthorization(config, chainCode);
        String requestBody = body == null ? "{}" : body.toString();
        try {
            String raw = restClient.put()
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
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                evictToken(config, chainCode);
            }
            log.error("Opera PUT failed. operation={}, hotelCode={}, url={}, status={}, requestBody={}, responseBody={}",
                    path, hotelCode, requestUrl, ex.getStatusCode().value(), safe(requestBody),
                    sanitize(ex.getResponseBodyAsString()), ex);
            throw new IllegalStateException("Opera posting request failed: " + ex.getStatusCode().value()
                    + " " + sanitize(ex.getResponseBodyAsString()), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Opera posting request failed", ex);
        }
    }

    @Override
    public JsonNode delete(OperaTenantConfigResolver.OperaResolvedConfig config,
                           String chainCode,
                           String hotelCode,
                           String path) {
        String requestUrl = normalizeUrl(config.baseUrl(), path);
        String authorization = resolveAuthorization(config, chainCode);
        try {
            String raw = restClient.delete()
                    .uri(requestUrl)
                    .header("x-app-key", config.appKey())
                    .header("x-hotelid", hotelCode)
                    .header("Authorization", authorization)
                    .retrieve()
                    .body(String.class);
            return parseJson(raw);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                evictToken(config, chainCode);
            }
            log.error("Opera DELETE failed. operation={}, hotelCode={}, url={}, status={}, responseBody={}",
                    path, hotelCode, requestUrl, ex.getStatusCode().value(), sanitize(ex.getResponseBodyAsString()), ex);
            throw new IllegalStateException("Opera posting request failed: " + ex.getStatusCode().value()
                    + " " + sanitize(ex.getResponseBodyAsString()), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Opera posting request failed", ex);
        }
    }

    private static String firstHeader(org.springframework.http.HttpHeaders headers, String name) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(name);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** A GET refused with 401 is retried once with a fresh token (the cached one may have been revoked). */
    private JsonNode getJson(String operation,
                             OperaTenantConfigResolver.OperaResolvedConfig config,
                             String chainCode,
                             String hotelCode,
                             String requestUrl) {
        for (int attempt = 1; ; attempt++) {
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
                if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value() && evictToken(config, chainCode) && attempt == 1) {
                    continue;
                }
                throw new IllegalStateException(operation + " failed: " + ex.getStatusCode().value()
                        + " " + sanitize(ex.getResponseBodyAsString()), ex);
            } catch (RestClientException ex) {
                throw new IllegalStateException(operation + " failed", ex);
            }
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
            if (ex.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                evictToken(config, chainCode);
            }
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
        CachedToken token = tokens.compute(tokenKey(config, chainCode), (key, cached) ->
                cached != null && clock.instant().isBefore(cached.renewAt()) ? cached : fetchAccessToken(config, chainCode));
        return "Bearer " + token.value();
    }

    /** Returns whether a cached token was dropped (false for a configured static token). */
    private boolean evictToken(OperaTenantConfigResolver.OperaResolvedConfig config, String chainCode) {
        if (StringUtils.hasText(config.accessToken())) {
            return false;
        }
        return tokens.remove(tokenKey(config, chainCode)) != null;
    }

    private static TokenKey tokenKey(OperaTenantConfigResolver.OperaResolvedConfig config, String chainCode) {
        return new TokenKey(config.baseUrl(), config.oauthPath(), config.appKey(), config.clientId(), config.clientSecret(),
                config.enterpriseId(), StringUtils.hasText(chainCode) ? chainCode.trim() : null);
    }

    private CachedToken fetchAccessToken(OperaTenantConfigResolver.OperaResolvedConfig config, String chainCode) {
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
            long expiresIn = json.path("expires_in").asLong(0);
            Duration lifetime = expiresIn > 0 ? Duration.ofSeconds(expiresIn) : TOKEN_DEFAULT_LIFETIME;
            Duration renewAfter = lifetime.compareTo(TOKEN_RENEW_MARGIN.multipliedBy(2)) > 0
                    ? lifetime.minus(TOKEN_RENEW_MARGIN)
                    : lifetime.dividedBy(2);
            return new CachedToken(accessToken.trim(), clock.instant().plus(renewAfter));
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
