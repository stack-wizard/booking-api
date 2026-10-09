package com.stackwizard.booking_api.security;

/**
 * Resolves the active booking tenant. The scope has two levels (see {@link TenantContext}):
 * <ul>
 *   <li>{@link #requireOrgTenantId()} — chain-level data (shared catalog, CRM, events)</li>
 *   <li>{@link #requirePropertyTenantId()} / {@link #requireTenantId()} — hotel-level data (capacity, operations)</li>
 * </ul>
 * When {@link TenantContext} is set (Platform JWT / m2m + X-Tenant-Id [+ X-Property-Id]) that value wins;
 * a client-supplied bigint is optional and only validated for mismatch.
 */
public final class TenantResolver {
    private TenantResolver() {
    }

    public static Long resolveTenantId(Long requestTenantId) {
        Long propertyTenantId = TenantContext.getPropertyTenantId();
        if (propertyTenantId != null) {
            if (requestTenantId != null && !requestTenantId.equals(propertyTenantId)) {
                throw new IllegalArgumentException("tenantId does not match token tenant");
            }
            return propertyTenantId;
        }
        if (TenantContext.getOrgTenantId() != null) {
            // Org-level request without a selected hotel: never fall back to the org id for hotel data.
            return null;
        }
        return requestTenantId;
    }

    /** Property (hotel) tenant from auth context, or from optional request value when unauthenticated. */
    public static Long requireTenantId() {
        return requireTenantId(null);
    }

    public static Long requireTenantId(Long requestTenantId) {
        Long resolved = resolveTenantId(requestTenantId);
        if (resolved == null) {
            if (TenantContext.getOrgTenantId() != null) {
                throw new IllegalArgumentException("propertyId is required: send X-Property-Id for this request");
            }
            throw new IllegalArgumentException("tenantId is required");
        }
        return resolved;
    }

    /** Explicit alias of {@link #requireTenantId()} for hotel-level data. */
    public static Long requirePropertyTenantId() {
        return requireTenantId(null);
    }

    /** Chain (parent) tenant for shared catalog / CRM / events. */
    public static Long requireOrgTenantId() {
        return requireOrgTenantId(null);
    }

    /**
     * Chain tenant for shared data. A client-supplied id is only validated: older clients still send the hotel
     * id, so the organization and the selected hotel are both accepted.
     */
    public static Long requireOrgTenantId(Long requestTenantId) {
        Long org = TenantContext.getOrgTenantId();
        if (org != null) {
            if (requestTenantId != null
                    && !requestTenantId.equals(org)
                    && !requestTenantId.equals(TenantContext.getPropertyTenantId())) {
                throw new IllegalArgumentException("tenantId does not match token tenant");
            }
            return org;
        }
        if (requestTenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return requestTenantId;
    }
}
