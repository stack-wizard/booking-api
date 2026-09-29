package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.service.PlatformTenantResolver;
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
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String ATTR_APP_USER = "booking.platformAppUser";

    private final PlatformTenantResolver tenantResolver;
    private final PlatformUserSyncService userSyncService;

    public PlatformAuthFilter(PlatformTenantResolver tenantResolver,
                              PlatformUserSyncService userSyncService) {
        this.tenantResolver = tenantResolver;
        this.userSyncService = userSyncService;
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
        Long bookingTenantId = tenantResolver.requireExisting(platformTenantId);
        TenantContext.setTenantId(bookingTenantId);

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

        UUID platformTenantId = resolvePlatformTenantId(request, jwt, platformAdmin);
        Long bookingTenantId = tenantResolver.resolveOrProvision(platformTenantId);
        TenantContext.setTenantId(bookingTenantId);

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

    private UUID resolvePlatformTenantId(HttpServletRequest request, Jwt jwt, boolean platformAdmin) {
        String header = request.getHeader(HEADER_TENANT_ID);
        if (header != null && !header.isBlank()) {
            UUID fromHeader = parseUuid(header.trim(), HEADER_TENANT_ID);
            if (!platformAdmin) {
                List<String> allow = tenantAllowList(jwt);
                boolean allowed = allow.stream().anyMatch(t -> t.equalsIgnoreCase(fromHeader.toString()));
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

    @SuppressWarnings("unchecked")
    private static List<String> stringListClaim(Jwt jwt, String claim) {
        Object raw = jwt.getClaim(claim);
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(o -> o == null ? null : o.toString())
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            return List.of(s.trim());
        }
        return List.of();
    }

    private static List<String> tenantAllowList(Jwt jwt) {
        List<String> many = stringListClaim(jwt, "mikos_tenant_ids");
        if (!many.isEmpty()) {
            return many;
        }
        String one = jwt.getClaimAsString("mikos_tenant_id");
        if (one != null && !one.isBlank()) {
            return List.of(one.trim());
        }
        return List.of();
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
