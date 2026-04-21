package com.stackwizard.booking_api.service;

/**
 * Published after a reservation amendment is applied successfully (transaction committed).
 */
public record ReservationRequestAmendedEvent(Long reservationRequestId) {
}
