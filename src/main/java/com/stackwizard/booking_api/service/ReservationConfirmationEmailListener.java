package com.stackwizard.booking_api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class ReservationConfirmationEmailListener {
    private static final Logger log = LoggerFactory.getLogger(ReservationConfirmationEmailListener.class);

    private final ReservationConfirmationEmailService reservationConfirmationEmailService;

    public ReservationConfirmationEmailListener(ReservationConfirmationEmailService reservationConfirmationEmailService) {
        this.reservationConfirmationEmailService = reservationConfirmationEmailService;
    }

    @Async("reservationConfirmationEmailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationRequestFinalized(ReservationRequestFinalizedEvent event) {
        try {
            reservationConfirmationEmailService.sendIfEligible(event.reservationRequestId());
        } catch (Exception ex) {
            log.error("Failed to send reservation confirmation email for request {}", event.reservationRequestId(), ex);
        }
    }
}
