package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.config.BookingDevProperties;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.service.PlatformTenantResolver;
import com.stackwizard.booking_api.service.PlatformTenantResolver.TenantScope;
import com.stackwizard.booking_api.service.PlatformUserSyncService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * After JWT authentication: enforce BOOKING entitlement / m2m scope, resolve tenant, sync user.
 * Registered only via {@link SecurityConfig} (servlet FilterRegistrationBean is disabled).
 */
@Component
public class PlatformAuthFilter extends OncePerRequestFilter {

    public static final String APP_CODE = "BOOKING";
    /** Platform UUID of the organization (hotel chain). A hotel UUID is still accepted for older clients. */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    /** Optional Platform UUID of the selected hotel (child of the organization). */
    public static final String HEADER_PROPERTY_ID = "X-Property-Id";
    public static final String ATTR_APP_USER = "booking.platformAppUser";
    /** Platform UUID of the organization on tenant-setup requests (the org may not be registered yet). */
    public static final String ATTR_PLATFORM_TENANT_ID = "booking.platformTenantId";
    static final String TENANT_SETUP_PATH = "/api/admin/tenant-setup";
    /** The organization list is what the UI needs to find the setup wizard, so it works before onboarding. */
    static final String TENANT_ORGANIZATIONS_PATH = "/api/tenant/organizations";

    private final PlatformTenantResolver tenantResolver;
    private final PlatformUserSyncService userSyncService;
    private final BookingDevProperties devProperties;

    public PlatformAuthFilter(PlatformTenantResolver tenantResolver,
                              PlatformUserSyncService userSyncService,
                              BookingDevProperties devProperties) {
        this.tenantResolver = tenantResolver;
        this.userSyncService = userSyncService;
        this.devProperties = devProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TenantContext.clear();
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Jwt jwt = extractJwt(auth);
            if (jwt != null) {
                authenticateRequest(request, jwt);
            }
            filterChain.doFilter(request, response);
        } catch (ResponseStatusException ex) {
            response.setStatus(ex.getStatusCode().value());
            response.setContentType("application/json");
            String msg = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
            response.getWriter().write("{\"message\":\"" + msg.replace("\"", "'") + "\"}");
        } finally {
            TenantContext.clear();
        }
    }

    /** Visible for tests. */
    void authenticateRequest(HttpServletRequest request, Jwt jwt) {
        applyPlatformAuth(request, jwt);
    }

    private static Jwt extractJwt(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        if (auth.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }

    private void applyPlatformAuth(HttpServletRequest request, Jwt jwt) {
        if (isM2m(jwt)) {
            applyM2m(request, jwt);
            return;
        }
        applyUser(request, jwt);
    }

    private void applyM2m(HttpServletRequest request, Jwt jwt) {
        if (!hasScope(jwt, "booking.api")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "m2m token requires scope booking.api");
        }
        UUID platformTenantId = requireHeaderTenantId(request);
        TenantScope scope = tenantResolver.resolveScope(platformTenantId, optionalHeaderPropertyId(request), false);
        TenantContext.setScope(scope.orgTenantId(), scope.propertyTenantId());

        Collection<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_API"));
        authorities.add(new SimpleGrantedAuthority("SCOPE_booking.api"));
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private void applyUser(HttpServletRequest request, Jwt jwt) {
        List<String> roles = stringListClaim(jwt, "mikos_roles");
        boolean platformAdmin = roles.stream().anyMatch(r -> "PLATFORM_ADMIN".equalsIgnoreCase(r));

        if (!platformAdmin) {
            List<String> apps = stringListClaim(jwt, "mikos_applications");
            boolean hasBooking = apps.stream().anyMatch(a -> APP_CODE.equalsIgnoreCase(a));
            if (!hasBooking) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "JWT must include " + APP_CODE + " in mikos_applications");
            }
        }

        UUID platformPropertyId = optionalHeaderPropertyId(request);
        UUID platformTenantId = resolvePlatformTenantId(request, jwt, platformAdmin, platformPropertyId);
        if (isTenantSetupRequest(request)) {
            applyTenantSetup(request, jwt, platformTenantId, roles);
            return;
        }
        TenantScope scope = tenantResolver.resolveScope(
                platformTenantId, platformPropertyId, devProperties.isAutoProvisionTenant());
        TenantContext.setScope(scope.orgTenantId(), scope.propertyTenantId());
        // People belong to the chain and work in several hotels.
        Long bookingTenantId = scope.orgTenantId();

        UUID platformUserId = parseUuid(jwt.getSubject(), "JWT sub");
        String username = firstNonBlank(
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("username"));

        AppUser appUser = userSyncService.syncUser(platformUserId, username, bookingTenantId, roles);
        request.setAttribute(ATTR_APP_USER, appUser);

        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (String name : PlatformRoleMapper.asAuthorityNames(appUser.getRole())) {
            authorities.add(new SimpleGrantedAuthority(name));
        }
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    /**
     * Membership on the organization (parent) grants every hotel under it; membership on a hotel grants
     * only that hotel, so the organization header is accepted when the selected hotel is in the allow-list.
     * That the hotel really is a child of the organization is verified by the resolver.
     */
    private static boolean isTenantSetupRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.contains(TENANT_SETUP_PATH) || uri.contains(TENANT_ORGANIZATIONS_PATH));
    }

    /**
     * Tenant setup works before the tenant is fully onboarded: no status check and no local user sync.
     * The controller verifies the caller's role.
     */
    private void applyTenantSetup(HttpServletRequest request, Jwt jwt, UUID platformTenantId, List<String> roles) {
        request.setAttribute(ATTR_PLATFORM_TENANT_ID, platformTenantId);
        tenantResolver.findOrgScopeForSetup(platformTenantId)
                .ifPresent(scope -> TenantContext.setScope(scope.orgTenantId(), null));
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, authorities, jwt.getSubject()));
    }

    private UUID resolvePlatformTenantId(HttpServletRequest request, Jwt jwt, boolean platformAdmin,
                                         UUID propertyId) {
        String header = request.getHeader(HEADER_TENANT_ID);
        if (header != null && !header.isBlank()) {
            UUID fromHeader = parseUuid(header.trim(), HEADER_TENANT_ID);
            if (!platformAdmin) {
                List<String> allow = tenantAllowList(jwt);
                boolean allowed = allow.stream().anyMatch(t -> t.equalsIgnoreCase(fromHeader.toString()))
                        || (propertyId != null
                        && allow.stream().anyMatch(t -> t.equalsIgnoreCase(propertyId.toString())));
                if (!allowed) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "X-Tenant-Id must match a tenant in your token allow-list");
                }
            }
            return fromHeader;
        }

        String claim = jwt.getClaimAsString("mikos_tenant_id");
        if (claim != null && !claim.isBlank()) {
            return parseUuid(claim.trim(), "mikos_tenant_id");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "X-Tenant-Id header or mikos_tenant_id claim is required");
    }

    private static UUID optionalHeaderPropertyId(HttpServletRequest request) {
        String header = request.getHeader(HEADER_PROPERTY_ID);
        if (header == null || header.isBlank()) {
            return null;
        }
        return parseUuid(header.trim(), HEADER_PROPERTY_ID);
    }

    private static UUID requireHeaderTenantId(HttpServletRequest request) {
        String header = request.getHeader(HEADER_TENANT_ID);
        if (header == null || header.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id header is required");
        }
        return parseUuid(header.trim(), HEADER_TENANT_ID);
    }

    private static boolean isM2m(Jwt jwt) {
        String tokenUse = jwt.getClaimAsString("token_use");
        return tokenUse != null && "m2m".equalsIgnoreCase(tokenUse.trim());
    }

    private static boolean hasScope(Jwt jwt, String required) {
        Object scope = jwt.getClaim("scope");
        if (scope instanceof String s) {
            for (String part : s.split("\\s+")) {
                if (required.equals(part)) {
                    return true;
                }
            }
        }
        if (scope instanceof Collection<?> col) {
            for (Object o : col) {
                if (o != null && required.equals(o.toString())) {
                    return true;
                }
            }
        }
        Object scp = jwt.getClaim("scp");
        if (scp instanceof Collection<?> col) {
            for (Object o : col) {
                if (o != null && required.equals(o.toString())) {
                    return true;
                }
            }
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            String authority = "SCOPE_" + required;
            return auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(a -> authority.equals(a) || required.equals(a));
        }
        return false;
    }

    private static List<String> stringListClaim(Jwt jwt, String claim) {
        return PlatformJwtClaims.stringList(jwt, claim);
    }

    private static List<String> tenantAllowList(Jwt jwt) {
        return PlatformJwtClaims.tenantAllowList(jwt);
    }

    private static UUID parseUuid(String raw, String label) {
        try {
            return UUID.fromString(raw.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be a UUID");
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
