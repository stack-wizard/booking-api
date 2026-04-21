package com.stackwizard.booking_api.service;

/**
 * Published after a cancellation request completes successfully (transaction committed).
 *
 * @param reservationRequestId reservation request id
 * @param cancellationRequestId completed cancellation row (source of settlement amounts for email)
 */
public record ReservationRequestCancelledEvent(Long reservationRequestId, Long cancellationRequestId) {
}
