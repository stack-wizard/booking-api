package com.stackwizard.booking_api.booking;

public final class BookingUom {
    private BookingUom() {}

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("uom is required");
        }
        return value.trim().toUpperCase();
    }
}
