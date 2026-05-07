package com.stackwizard.booking_api.model;

/**
 * OHIP deposit {@code postPayment} outcome for a {@link ReservationRequest} (one payment per request).
 */
public enum OperaDepositPostStatus {
    /** No deposit amount to mirror. */
    SKIPPED,
    POSTED,
    FAILED
}
