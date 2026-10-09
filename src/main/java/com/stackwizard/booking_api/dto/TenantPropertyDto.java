package com.stackwizard.booking_api.dto;

import java.util.UUID;

/** A hotel (child tenant) of the current organization. */
public record TenantPropertyDto(
        Long tenantId,
        UUID platformTenantId,
        String name,
        String hotelCode,
        String timezone,
        String status) {
}
