package com.stackwizard.booking_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Development-only knobs. {@link #referenceDate} is only honored when running with profile {@code dev} or {@code local}
 * (see {@link com.stackwizard.booking_api.support.BookingDevTimeSupport}).
 */
@ConfigurationProperties(prefix = "booking.dev")
public class BookingDevProperties {

    /**
     * Platform tenant "Demo Hotel (DH)" — has hotel code {@code DH} under group {@code 11111111-…}.
     * Seed targets this property tenant, not the parent group.
     */
    public static final UUID DEFAULT_DEMO_PLATFORM_TENANT_ID =
            UUID.fromString("21111111-1111-1111-1111-111111111111");

    /**
     * When set and profile is dev/local, used instead of {@link LocalDate#now()} for selected defaults
     * (e.g. resource map {@code /periods} when {@code fromDate} is omitted).
     */
    private LocalDate referenceDate;

    /**
     * When true (default), startup seeds one demo sales flow for {@link #dhSalesFlowPlatformTenantId}
     * if that Platform tenant is mapped. An older/incomplete seed (wrong tenant or missing hotel) is wiped
     * and re-seeded. No-op when mapping is missing. Disable via SSM on real prod.
     */
    private boolean dhSalesFlowSeed = true;

    /**
     * Platform tenant UUID ({@code X-Tenant-Id}). Resolved to local {@code tenant_id} via
     * {@code platform_tenant_mapping} — not an Opera hotel code.
     */
    private UUID dhSalesFlowPlatformTenantId = DEFAULT_DEMO_PLATFORM_TENANT_ID;

    /**
     * Local/dev convenience only: create an ORG mapping on first login of an unmapped Platform tenant.
     * Default false — real tenants are registered through {@code /api/admin/tenant-setup}.
     */
    private boolean autoProvisionTenant = false;

    public boolean isAutoProvisionTenant() {
        return autoProvisionTenant;
    }

    public void setAutoProvisionTenant(boolean autoProvisionTenant) {
        this.autoProvisionTenant = autoProvisionTenant;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public void setReferenceDate(LocalDate referenceDate) {
        this.referenceDate = referenceDate;
    }

    public boolean isDhSalesFlowSeed() {
        return dhSalesFlowSeed;
    }

    public void setDhSalesFlowSeed(boolean dhSalesFlowSeed) {
        this.dhSalesFlowSeed = dhSalesFlowSeed;
    }

    public UUID getDhSalesFlowPlatformTenantId() {
        return dhSalesFlowPlatformTenantId;
    }

    public void setDhSalesFlowPlatformTenantId(UUID dhSalesFlowPlatformTenantId) {
        this.dhSalesFlowPlatformTenantId = dhSalesFlowPlatformTenantId;
    }
}
