package com.stackwizard.booking_api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class ReservationCancellationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationCancellationEmailListener.class);

    private final ReservationNotificationEmailService reservationNotificationEmailService;

    public ReservationCancellationEmailListener(ReservationNotificationEmailService reservationNotificationEmailService) {
        this.reservationNotificationEmailService = reservationNotificationEmailService;
    }

    @Async("reservationConfirmationEmailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationCancelled(ReservationRequestCancelledEvent event) {
        try {
            reservationNotificationEmailService.sendCancellationEmail(
                    event.reservationRequestId(),
                    event.cancellationRequestId());
        } catch (Exception ex) {
            log.error("Failed to send cancellation email for reservation request {}", event.reservationRequestId(), ex);
        }
    }
}
