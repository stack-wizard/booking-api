package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.TenantSetupStatusDto;
import com.stackwizard.booking_api.dto.TenantSetupStatusDto.PropertyStatus;
import com.stackwizard.booking_api.security.PlatformAuthFilter;
import com.stackwizard.booking_api.security.PlatformJwtClaims;
import com.stackwizard.booking_api.service.TenantSetupService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Tenant onboarding. {@code X-Tenant-Id} is the Platform UUID of the organization. These routes are reachable
 * before the tenant is onboarded (see PlatformAuthFilter) and require a tenant administrator.
 */
@RestController
@RequestMapping("/api/admin/tenant-setup")
public class TenantSetupController {
    private final TenantSetupService service;

    public TenantSetupController(TenantSetupService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public TenantSetupStatusDto status(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        requireAdmin(jwt);
        return service.status(requireOrgPlatformId(request));
    }

    /** Registers the organization and its Platform hotels (hotels start in SETUP). */
    @PostMapping("/org")
    public TenantSetupStatusDto register(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        requireAdmin(jwt);
        return service.registerOrSync(requireOrgPlatformId(request), jwt.getTokenValue());
    }

    /** Re-reads the hotels from Platform, e.g. after a hotel was added there. */
    @PostMapping("/properties/sync")
    public TenantSetupStatusDto sync(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        requireAdmin(jwt);
        return service.registerOrSync(requireOrgPlatformId(request), jwt.getTokenValue());
    }

    @PostMapping("/properties/{tenantId}/activate")
    public PropertyStatus activate(@PathVariable Long tenantId,
                                   @AuthenticationPrincipal Jwt jwt,
                                   HttpServletRequest request) {
        requireAdmin(jwt);
        return service.activate(requireOrgPlatformId(request), tenantId);
    }

    /** Moves the hotel's catalog, CRM, events and sales documents to the organization (one-off, all or nothing). */
    @PostMapping("/properties/{tenantId}/promote-catalog")
    public TenantSetupStatusDto promoteCatalog(@PathVariable Long tenantId,
                                               @AuthenticationPrincipal Jwt jwt,
                                               HttpServletRequest request) {
        requireAdmin(jwt);
        UUID orgPlatformId = requireOrgPlatformId(request);
        service.promoteCatalogToOrg(orgPlatformId, tenantId);
        return service.status(orgPlatformId);
    }

    private static UUID requireOrgPlatformId(HttpServletRequest request) {
        Object attr = request.getAttribute(PlatformAuthFilter.ATTR_PLATFORM_TENANT_ID);
        if (attr instanceof UUID id) {
            return id;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id header is required");
    }

    private static void requireAdmin(Jwt jwt) {
        boolean ok = jwt != null && PlatformJwtClaims.stringList(jwt, "mikos_roles").stream().anyMatch(r ->
                "PLATFORM_ADMIN".equalsIgnoreCase(r)
                        || "TENANT_ADMIN".equalsIgnoreCase(r)
                        || "BOOKING_ADMIN".equalsIgnoreCase(r));
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant administrator role required");
        }
    }
}
