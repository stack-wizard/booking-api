package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.AppUser;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Maps Mikos Platform JWT {@code mikos_roles} to local {@link AppUser.Role}.
 * Highest matching role wins.
 */
public final class PlatformRoleMapper {

    private PlatformRoleMapper() {
    }

    public static AppUser.Role mapHighest(Collection<String> mikosRoles) {
        if (mikosRoles == null || mikosRoles.isEmpty()) {
            return null;
        }
        boolean superAdmin = false;
        boolean admin = false;
        boolean staff = false;
        boolean cashier = false;
        for (String raw : mikosRoles) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String role = raw.trim().toUpperCase(Locale.ROOT);
            switch (role) {
                case "PLATFORM_ADMIN" -> superAdmin = true;
                case "BOOKING_ADMIN" -> admin = true;
                case "BOOKING_STAFF" -> staff = true;
                case "BOOKING_CASHIER" -> cashier = true;
                default -> {
                    // ignore other platform product roles
                }
            }
        }
        if (superAdmin) {
            return AppUser.Role.SUPER_ADMIN;
        }
        if (admin) {
            return AppUser.Role.ADMIN;
        }
        if (staff) {
            return AppUser.Role.STAFF;
        }
        if (cashier) {
            return AppUser.Role.CASHIER;
        }
        return null;
    }

    public static List<String> asAuthorityNames(AppUser.Role role) {
        if (role == null) {
            return List.of();
        }
        return List.of("ROLE_" + role.name());
    }
}
