package com.stackwizard.booking_api.service.fiscal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class OfisFiscalizationClient {
    private static final Logger log = LoggerFactory.getLogger(OfisFiscalizationClient.class);

    private final OfisTenantConfigResolver configResolver;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);

    public OfisFiscalizationClient(OfisTenantConfigResolver configResolver) {
        this.configResolver = configResolver;
        this.restClient = RestClient.builder().build();
    }

    public JsonNode fiscalize(Long tenantId, JsonNode payload) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("OFIS payload is required");
        }

        OfisTenantConfigResolver.OfisResolvedConfig config = configResolver.resolve(tenantId);
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString((config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8));
        String url = normalizeUrl(config.baseUrl(), config.fiscalizationPath());
        String requestBody = serializeJson(payload, "OFIS fiscalization request");

        try {
            String raw = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", authorization)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            return parseJson(raw, "OFIS fiscalization");
        } catch (HttpClientErrorException ex) {
            log.error("OFIS fiscalization failed for tenant {}. Status={} body={}",
                    tenantId,
                    ex.getStatusCode(),
                    ex.getResponseBodyAsString(), ex);
            throw new IllegalStateException("OFIS fiscalization failed: " + ex.getResponseBodyAsString(), ex);
        } catch (RestClientException ex) {
            log.error("OFIS fiscalization failed for tenant {}", tenantId, ex);
            throw new IllegalStateException("OFIS fiscalization failed", ex);
        }
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

    private String serializeJson(JsonNode payload, String operation) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException(operation + " payload serialization failed", ex);
        }
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
}
