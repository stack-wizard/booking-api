package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the tenant hierarchy from Mikos Platform with the caller's own bearer token
 * (booking has no credentials of its own for this).
 */
@Component
public class PlatformDirectoryClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlatformChild(UUID id, String name, String hotelCode, boolean active) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlatformTenantSummary(UUID id, String name, boolean active, String hotelCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlatformRole(UUID id, String name) {
    }

    /** A user of a Platform tenant ({@code /api/v1/tenant/me/users}); {@code userId} is the JWT {@code sub}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlatformUser(UUID userId, String username, boolean enabled, List<PlatformRole> roles,
                               List<String> applicationCodes) {
    }

    private final RestClient restClient;

    public PlatformDirectoryClient(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String platformBaseUrl) {
        String base = platformBaseUrl.endsWith("/")
                ? platformBaseUrl.substring(0, platformBaseUrl.length() - 1)
                : platformBaseUrl;
        this.restClient = RestClient.builder().baseUrl(base).build();
    }

    /** Active child hotels of the organization ({@code /api/v1/tenant/me/children}). */
    public List<PlatformChild> listChildren(UUID orgPlatformId, String bearerToken) {
        try {
            List<PlatformChild> children = restClient.get()
                    .uri("/api/v1/tenant/me/children")
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("X-Tenant-Id", orgPlatformId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PlatformChild>>() { });
            return children == null ? List.of() : children;
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not read hotels from Mikos Platform: " + ex.getMessage());
        }
    }

    /** Display name of a tenant the user is a member of; empty when unavailable (best effort). */
    public Optional<PlatformTenantSummary> findTenant(UUID platformTenantId, String bearerToken) {
        try {
            List<PlatformTenantSummary> tenants = restClient.get()
                    .uri("/api/v1/me/tenants")
                    .header("Authorization", "Bearer " + bearerToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PlatformTenantSummary>>() { });
            if (tenants == null) {
                return Optional.empty();
            }
            return tenants.stream().filter(t -> platformTenantId.equals(t.id())).findFirst();
        } catch (RestClientException ex) {
            return Optional.empty();
        }
    }

    /**
     * Users of one Platform tenant (organization or hotel). Needs a caller who administers users of that tenant
     * in Platform (TENANT_ADMIN or tenant:users:manage); otherwise the call is refused with 403.
     */
    public List<PlatformUser> listUsers(UUID platformTenantId, String bearerToken) {
        try {
            List<PlatformUser> users = restClient.get()
                    .uri("/api/v1/tenant/me/users")
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("X-Tenant-Id", platformTenantId.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PlatformUser>>() { });
            return users == null ? List.of() : users;
        } catch (HttpClientErrorException.Forbidden ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Listing users needs the TENANT_ADMIN role (or tenant:users:manage) in Mikos Platform");
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not read users from Mikos Platform: " + ex.getMessage());
        }
    }
}
