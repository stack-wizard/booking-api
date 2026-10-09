package com.stackwizard.booking_api.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/** Read helpers for the Mikos Platform claims ({@code mikos_roles}, {@code mikos_tenant_ids}, ...). */
public final class PlatformJwtClaims {
    private PlatformJwtClaims() {
    }

    public static List<String> stringList(Jwt jwt, String claim) {
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

    public static boolean isPlatformAdmin(Jwt jwt) {
        return stringList(jwt, "mikos_roles").stream().anyMatch(r -> "PLATFORM_ADMIN".equalsIgnoreCase(r));
    }

    /** Platform tenant UUIDs the user has a membership in. */
    public static List<String> tenantAllowList(Jwt jwt) {
        List<String> many = stringList(jwt, "mikos_tenant_ids");
        if (!many.isEmpty()) {
            return many;
        }
        String one = jwt.getClaimAsString("mikos_tenant_id");
        if (one != null && !one.isBlank()) {
            return List.of(one.trim());
        }
        return List.of();
    }
}
