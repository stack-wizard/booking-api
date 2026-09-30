package com.stackwizard.booking_api.security;

/**
 * Resolves the active booking tenant. When {@link TenantContext} is set (Platform JWT /
 * m2m + X-Tenant-Id), that value wins; a client-supplied bigint is optional and only
 * validated for mismatch. Callers should prefer {@link #requireTenantId()} and stop
 * requiring local tenant ids from CMS/clients.
 */
public final class TenantResolver {
    private TenantResolver() {
    }

    public static Long resolveTenantId(Long requestTenantId) {
        Long tokenTenantId = TenantContext.getTenantId();
        if (tokenTenantId != null) {
            if (requestTenantId != null && !requestTenantId.equals(tokenTenantId)) {
                throw new IllegalArgumentException("tenantId does not match token tenant");
            }
            return tokenTenantId;
        }
        return requestTenantId;
    }

    /** Tenant from auth context, or from optional request value when unauthenticated. */
    public static Long requireTenantId() {
        return requireTenantId(null);
    }

    public static Long requireTenantId(Long requestTenantId) {
        Long resolved = resolveTenantId(requestTenantId);
        if (resolved == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return resolved;
    }
}
