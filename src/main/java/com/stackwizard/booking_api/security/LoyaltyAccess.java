package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loyalty roles come from Platform ({@code mikos_roles}): BOOKING_LOYALTY_OWNER runs the program, BOOKING_LOYALTY_SUPPORT and
 * booking staff may only look members up (FR-07.4). Booking ADMIN / SUPER_ADMIN have everything.
 */
@Component
public class LoyaltyAccess {
    public static final String ROLE_LOYALTY_OWNER = "BOOKING_LOYALTY_OWNER";
    public static final String ROLE_LOYALTY_SUPPORT = "BOOKING_LOYALTY_SUPPORT";

    private final AuthUserAccessor authUserAccessor;

    public LoyaltyAccess(AuthUserAccessor authUserAccessor) {
        this.authUserAccessor = authUserAccessor;
    }

    public void require(LoyaltyPermission permission) {
        if (!permissions().contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing loyalty permission: " + permission);
        }
    }

    public Long currentUserId() {
        return authUserAccessor.currentAppUser().map(AppUser::getId).orElse(null);
    }

    Set<LoyaltyPermission> permissions() {
        AppUser.Role bookingRole = authUserAccessor.currentAppUser().map(AppUser::getRole).orElse(null);
        return permissionsFor(bookingRole, mikosRolesFromJwt());
    }

    static Set<LoyaltyPermission> permissionsFor(AppUser.Role bookingRole, Collection<String> mikosRoles) {
        if (bookingRole == AppUser.Role.SUPER_ADMIN || bookingRole == AppUser.Role.ADMIN) {
            return EnumSet.allOf(LoyaltyPermission.class);
        }
        EnumSet<LoyaltyPermission> out = EnumSet.noneOf(LoyaltyPermission.class);
        if (bookingRole == AppUser.Role.STAFF) {
            out.add(LoyaltyPermission.READ);
        }
        for (String raw : mikosRoles) {
            String role = raw.trim().toUpperCase(Locale.ROOT);
            if (ROLE_LOYALTY_OWNER.equals(role)) {
                out.addAll(EnumSet.of(LoyaltyPermission.READ, LoyaltyPermission.ADJUST, LoyaltyPermission.CONFIG, LoyaltyPermission.IMPORT));
            } else if (ROLE_LOYALTY_SUPPORT.equals(role)) {
                out.add(LoyaltyPermission.READ);
            }
        }
        return out;
    }

    private static Collection<String> mikosRolesFromJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return List.of();
        }
        Object raw = jwt.getClaim("mikos_roles");
        if (raw instanceof List<?> list) {
            return list.stream().map(o -> o == null ? null : o.toString()).filter(s -> s != null && !s.isBlank()).toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            return List.of(s.trim());
        }
        return List.of();
    }
}
