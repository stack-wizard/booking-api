package com.stackwizard.booking_api.security;

/**
 * Per-request tenant scope. Two levels:
 * <ul>
 *   <li><b>org</b> — hotel chain (Platform parent tenant); owner of shared catalog / CRM / events</li>
 *   <li><b>property</b> — hotel (Platform child tenant); owner of capacity and operations</li>
 * </ul>
 * {@link #getTenantId()} is the effective (property) tenant and is what legacy code reads.
 * A chain without any child hotel works as its own property (legacy single-tenant data).
 */
public final class TenantContext {
    private static final ThreadLocal<Long> ORG_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> PROPERTY_TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    /** Sets both levels to the same tenant (standalone tenant / tests). */
    public static void setTenantId(Long tenantId) {
        ORG_TENANT_ID.set(tenantId);
        PROPERTY_TENANT_ID.set(tenantId);
    }

    /** @param propertyTenantId may be null when the request has no hotel selected (org-level call). */
    public static void setScope(Long orgTenantId, Long propertyTenantId) {
        ORG_TENANT_ID.set(orgTenantId);
        PROPERTY_TENANT_ID.set(propertyTenantId);
    }

    /** Effective tenant: the selected property, or null when only the org is known. */
    public static Long getTenantId() {
        return PROPERTY_TENANT_ID.get();
    }

    public static Long getPropertyTenantId() {
        return PROPERTY_TENANT_ID.get();
    }

    public static Long getOrgTenantId() {
        return ORG_TENANT_ID.get();
    }

    public static void clear() {
        ORG_TENANT_ID.remove();
        PROPERTY_TENANT_ID.remove();
    }
}
