package com.stackwizard.booking_api.service.opera;

import com.stackwizard.booking_api.model.OperaCheckInLineStatus;
import com.stackwizard.booking_api.model.OperaDepositPostStatus;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

/**
 * Persists OHIP check-in progress in independent transactions so partial success survives
 * rollback of the outer check-in transaction and can be retried.
 */
@Service
public class OperaCheckInProgressService {

    private final ReservationRepository reservationRepository;
    private final ReservationRequestRepository reservationRequestRepository;

    public OperaCheckInProgressService(ReservationRepository reservationRepository,
                                       ReservationRequestRepository reservationRequestRepository) {
        this.reservationRepository = reservationRepository;
        this.reservationRequestRepository = reservationRequestRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReservationCreated(Long reservationId, Long operaReservationId) {
        Reservation r = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalStateException("Reservation not found: " + reservationId));
        r.setOperaReservationId(operaReservationId);
        r.setOperaCheckInStatus(OperaCheckInLineStatus.RESERVATION_CREATED);
        r.setOperaCheckInError(null);
        reservationRepository.save(r);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCheckInSuccess(Long reservationId) {
        Reservation r = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalStateException("Reservation not found: " + reservationId));
        r.setOperaCheckInStatus(OperaCheckInLineStatus.CHECKIN_COMPLETE);
        r.setOperaCheckInError(null);
        r.setOperaCheckInAt(OffsetDateTime.now());
        reservationRepository.save(r);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCheckInFailure(Long reservationId, String errorMessage) {
        Reservation r = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalStateException("Reservation not found: " + reservationId));
        r.setOperaCheckInStatus(OperaCheckInLineStatus.CHECKIN_FAILED);
        r.setOperaCheckInError(truncateError(errorMessage));
        reservationRepository.save(r);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mergeOperaProfileId(Long reservationRequestId, String profileId) {
        if (!StringUtils.hasText(profileId)) {
            return;
        }
        ReservationRequest req = reservationRequestRepository.findById(reservationRequestId)
                .orElseThrow(() -> new IllegalStateException("Reservation request not found: " + reservationRequestId));
        if (!StringUtils.hasText(req.getOperaProfileId())) {
            req.setOperaProfileId(profileId.trim());
            reservationRequestRepository.save(req);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDepositPosted(Long reservationRequestId) {
        ReservationRequest req = reservationRequestRepository.findById(reservationRequestId)
                .orElseThrow(() -> new IllegalStateException("Reservation request not found: " + reservationRequestId));
        req.setOperaDepositPostStatus(OperaDepositPostStatus.POSTED);
        req.setOperaDepositPostAt(OffsetDateTime.now());
        req.setOperaDepositPostError(null);
        reservationRequestRepository.save(req);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDepositFailed(Long reservationRequestId, String errorMessage) {
        ReservationRequest req = reservationRequestRepository.findById(reservationRequestId)
                .orElseThrow(() -> new IllegalStateException("Reservation request not found: " + reservationRequestId));
        req.setOperaDepositPostStatus(OperaDepositPostStatus.FAILED);
        req.setOperaDepositPostError(truncateError(errorMessage));
        reservationRequestRepository.save(req);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDepositSkipped(Long reservationRequestId) {
        ReservationRequest req = reservationRequestRepository.findById(reservationRequestId)
                .orElseThrow(() -> new IllegalStateException("Reservation request not found: " + reservationRequestId));
        if (req.getOperaDepositPostStatus() == null || req.getOperaDepositPostStatus() != OperaDepositPostStatus.POSTED) {
            req.setOperaDepositPostStatus(OperaDepositPostStatus.SKIPPED);
            req.setOperaDepositPostAt(OffsetDateTime.now());
            req.setOperaDepositPostError(null);
            reservationRequestRepository.save(req);
        }
    }

    private static String truncateError(String message) {
        if (message == null) {
            return null;
        }
        String t = message.trim();
        if (t.length() <= 4000) {
            return t;
        }
        return t.substring(0, 3997) + "...";
    }
}
