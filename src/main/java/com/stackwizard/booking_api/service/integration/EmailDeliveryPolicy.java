package com.stackwizard.booking_api.service.integration;

import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.TenantConfigRepository;
import org.springframework.stereotype.Component;

/**
 * Whether booking-api itself sends guest e-mails for a tenant. A hotel inherits its organization's setting
 * unless its own {@code tenant_config} says EXTERNAL_EVENTS.
 */
@Component
public class EmailDeliveryPolicy {
    public static final String EXTERNAL_EVENTS = "EXTERNAL_EVENTS";

    private final TenantConfigRepository tenantConfigRepo;
    private final PlatformTenantMappingRepository mappingRepo;

    public EmailDeliveryPolicy(TenantConfigRepository tenantConfigRepo, PlatformTenantMappingRepository mappingRepo) {
        this.tenantConfigRepo = tenantConfigRepo;
        this.mappingRepo = mappingRepo;
    }

    public boolean isExternal(Long tenantId) {
        if (tenantId == null) {
            return false;
        }
        if (externalFor(tenantId)) {
            return true;
        }
        Long parent = mappingRepo.findByTenantId(tenantId).map(m -> m.getParentTenantId()).orElse(null);
        return parent != null && externalFor(parent);
    }

    private boolean externalFor(Long tenantId) {
        return tenantConfigRepo.findByTenantId(tenantId)
                .map(c -> EXTERNAL_EVENTS.equals(c.getEmailDelivery()))
                .orElse(false);
    }
}
