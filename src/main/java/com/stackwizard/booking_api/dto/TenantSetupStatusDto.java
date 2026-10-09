package com.stackwizard.booking_api.dto;

import java.util.List;
import java.util.UUID;

/** Onboarding checklist for an organization and its hotels. */
public record TenantSetupStatusDto(
        boolean registered,
        UUID platformTenantId,
        Long tenantId,
        String name,
        List<PropertyStatus> properties) {

    public record PropertyStatus(
            Long tenantId,
            UUID platformTenantId,
            String name,
            String hotelCode,
            String status,
            boolean readyToActivate,
            List<Step> steps) {
    }

    /** {@code required} steps block activation; the others are recommendations. */
    public record Step(String code, String label, boolean required, boolean done) {
    }
}
