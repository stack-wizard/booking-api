package com.stackwizard.booking_api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CheckoutConflictError(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> blockers) {
}
