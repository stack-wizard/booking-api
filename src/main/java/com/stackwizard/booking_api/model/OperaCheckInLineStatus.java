package com.stackwizard.booking_api.model;

/**
 * OHIP sync progress for a single booking {@link Reservation} line.
 */
public enum OperaCheckInLineStatus {
    /** PMS reservation exists; check-in not yet confirmed in our DB. */
    RESERVATION_CREATED,
    CHECKIN_COMPLETE,
    CHECKIN_FAILED
}
