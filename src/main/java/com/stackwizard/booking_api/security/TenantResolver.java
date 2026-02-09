package com.stackwizard.booking_api.security;

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

    public static Long requireTenantId(Long requestTenantId) {
        Long resolved = resolveTenantId(requestTenantId);
        if (resolved == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return resolved;
    }
}
