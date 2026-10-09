package com.stackwizard.booking_api.dto;

import java.util.List;
import java.util.UUID;

/**
 * An organization (hotel chain) the caller belongs to, directly or through one of its hotels. An organization
 * that is not onboarded yet has no {@code tenantId} and no hotels; the admin completes it in the setup wizard.
 */
public record TenantOrganizationDto(
        UUID platformTenantId,
        Long tenantId,
        String name,
        boolean onboarded,
        List<UUID> propertyPlatformTenantIds) {
}
