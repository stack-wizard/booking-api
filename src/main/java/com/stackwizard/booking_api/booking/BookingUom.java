package com.stackwizard.booking_api.booking;

public enum BookingUom {
    DAY,
    HOUR;

    public static BookingUom from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("uom is required");
        }
        return BookingUom.valueOf(value.trim().toUpperCase());
    }
}
