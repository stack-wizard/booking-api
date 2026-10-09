package com.stackwizard.booking_api.dto;

import com.stackwizard.booking_api.model.Product;

import java.util.List;
import java.util.UUID;

public final class ProductVisibilityDtos {
    private ProductVisibilityDtos() {
    }

    /** {@code visible} is the effective result for the hotel; {@code explicit} tells whether a switch exists. */
    public record PropertyVisibility(Long tenantId, UUID platformTenantId, String hotelCode, String name,
                                     boolean visible, boolean explicit) {
    }

    public record Response(Product.PropertyVisibility propertyVisibility, List<PropertyVisibility> properties) {
    }

    public record PropertySwitch(Long tenantId, Boolean visible) {
    }

    /** Hotels not listed keep the default of the chosen mode. */
    public record Request(Product.PropertyVisibility propertyVisibility, List<PropertySwitch> properties) {
    }
}
