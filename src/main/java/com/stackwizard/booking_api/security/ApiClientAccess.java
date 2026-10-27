package com.stackwizard.booking_api.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * Channel (machine-to-machine) callers: {@link PlatformAuthFilter} grants {@value #ROLE_API} only to m2m tokens
 * with scope {@code booking.api}. User JWTs never carry it; the token itself must also be {@code token_use=m2m}.
 */
public final class ApiClientAccess {
    public static final String ROLE_API = "ROLE_API";

    private ApiClientAccess() {
    }

    public static void requireApiClient() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "m2m token required");
        }
        boolean api = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(ROLE_API::equals);
        if (!api || !isM2mToken(auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only m2m channel clients may call this endpoint");
        }
    }

    private static boolean isM2mToken(Authentication auth) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        String tokenUse = jwt.getClaimAsString("token_use");
        return tokenUse != null && "m2m".equalsIgnoreCase(tokenUse.trim());
    }
}
