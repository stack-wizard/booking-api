package com.stackwizard.booking_api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class ReservationAmendmentEmailListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationAmendmentEmailListener.class);

    private final ReservationNotificationEmailService reservationNotificationEmailService;

    public ReservationAmendmentEmailListener(ReservationNotificationEmailService reservationNotificationEmailService) {
        this.reservationNotificationEmailService = reservationNotificationEmailService;
    }

    @Async("reservationConfirmationEmailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationAmended(ReservationRequestAmendedEvent event) {
        try {
            reservationNotificationEmailService.sendAmendmentEmail(event.reservationRequestId());
        } catch (Exception ex) {
            log.error("Failed to send amendment email for reservation request {}", event.reservationRequestId(), ex);
        }
    }
}
