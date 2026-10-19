package com.stackwizard.booking_api.service.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads a tenant's OHIP credentials from Mikos Platform
 * ({@code GET /api/v1/service/integrations/ohip}). Platform resolves chain-level storage from the
 * effective property tenant id. Auth / network failures return empty so local config can be used.
 */
@Component
public class PlatformOhipClient {
    private static final Logger log = LoggerFactory.getLogger(PlatformOhipClient.class);

    private final RestClient platform;
    private final PlatformServiceTokenClient serviceTokens;

    public PlatformOhipClient(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String platformBaseUrl,
            PlatformServiceTokenClient serviceTokens) {
        String base = platformBaseUrl.endsWith("/")
                ? platformBaseUrl.substring(0, platformBaseUrl.length() - 1)
                : platformBaseUrl;
        this.platform = RestClient.builder().baseUrl(base).build();
        this.serviceTokens = serviceTokens;
    }

    public Optional<PlatformOhipCredentials> fetchAsService(UUID platformTenantId) {
        if (platformTenantId == null || !serviceTokens.isConfigured()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(getServiceCredentials(platformTenantId));
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (RestClientException ex) {
            if (ex instanceof HttpClientErrorException http && http.getStatusCode().value() == 401) {
                serviceTokens.invalidate();
                try {
                    return Optional.ofNullable(getServiceCredentials(platformTenantId));
                } catch (HttpClientErrorException.NotFound retry404) {
                    return Optional.empty();
                } catch (RestClientException retry) {
                    log.warn("Platform OHIP read failed after token retry: {}", retry.getMessage());
                    return Optional.empty();
                }
            }
            log.warn("Platform OHIP read failed for {}: {}", platformTenantId, ex.getMessage());
            return Optional.empty();
        }
    }

    private PlatformOhipCredentials getServiceCredentials(UUID platformTenantId) {
        return platform.get()
                .uri(b -> b.path("/api/v1/service/integrations/ohip")
                        .queryParam("tenantId", platformTenantId)
                        .build())
                .header("Authorization", "Bearer " + serviceTokens.accessToken())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(PlatformOhipCredentials.class);
    }
}
