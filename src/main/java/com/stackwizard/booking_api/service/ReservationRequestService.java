package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.ReservationRequestAccessToken;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ReservationRequestService {
    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final AllocationRepository allocationRepo;
    private final PaymentIntentRepository paymentIntentRepo;
    private final ReservationRequestAccessTokenService accessTokenService;

    public ReservationRequestService(ReservationRequestRepository requestRepo,
                                     ReservationRepository reservationRepo,
                                     AllocationRepository allocationRepo,
                                     PaymentIntentRepository paymentIntentRepo,
                                     ReservationRequestAccessTokenService accessTokenService) {
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.allocationRepo = allocationRepo;
        this.paymentIntentRepo = paymentIntentRepo;
        this.accessTokenService = accessTokenService;
    }

    public List<ReservationRequest> findAll() { return requestRepo.findAll(); }
    public Optional<ReservationRequest> findById(Long id) { return requestRepo.findById(id); }
    public ReservationRequest save(ReservationRequest request) { return requestRepo.save(request); }

    @Transactional
    public ReservationRequest findByPublicAccessToken(String token) {
        ReservationRequestAccessToken accessToken = accessTokenService.requireValidToken(token);
        ReservationRequest request = requestRepo.findById(accessToken.getReservationRequestId())
                .orElseThrow(() -> new IllegalArgumentException("Reservation request not found"));
        if (request.getStatus() != ReservationRequest.Status.FINALIZED) {
            throw new IllegalStateException("Reservation request is not finalized");
        }
        return request;
    }

    @Transactional
    public ReservationRequest updateCustomerData(Long requestId, String customerName, String customerEmail, String customerPhone) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (request.getStatus() == ReservationRequest.Status.CANCELLED
                || request.getStatus() == ReservationRequest.Status.FINALIZED) {
            throw new IllegalStateException("Customer data cannot be updated for request in status " + request.getStatus());
        }

        boolean updateName = customerName != null;
        boolean updateEmail = customerEmail != null;
        boolean updatePhone = customerPhone != null;
        if (!updateName && !updateEmail && !updatePhone) {
            return request;
        }

        if (updateName) {
            request.setCustomerName(normalizeNullable(customerName));
        }
        if (updateEmail) {
            request.setCustomerEmail(normalizeNullable(customerEmail));
        }
        if (updatePhone) {
            request.setCustomerPhone(normalizeNullable(customerPhone));
        }
        ReservationRequest saved = requestRepo.save(request);

        List<Reservation> reservations = reservationRepo.findByRequestId(requestId);
        if (!reservations.isEmpty()) {
            for (Reservation reservation : reservations) {
                if (updateName) {
                    reservation.setCustomerName(saved.getCustomerName());
                }
                if (updateEmail) {
                    reservation.setCustomerEmail(saved.getCustomerEmail());
                }
                if (updatePhone) {
                    reservation.setCustomerPhone(saved.getCustomerPhone());
                }
            }
            reservationRepo.saveAll(reservations);
        }
        return saved;
    }

    @Transactional
    public ReservationRequest cancelPaymentForRequest(Long requestId) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));

        if (request.getStatus() == ReservationRequest.Status.PENDING_PAYMENT) {
            cancelPaymentAttempt(request);
            return request;
        }
        if (request.getStatus() == ReservationRequest.Status.DRAFT
                || request.getStatus() == ReservationRequest.Status.CANCELLED) {
            return request;
        }
        throw new IllegalStateException("Cannot cancel payment for request in status " + request.getStatus());
    }

    @Transactional
    public void deleteDraftRequest(Long requestId) {
        ReservationRequest request = requestRepo.findById(requestId).orElse(null);
        if (request == null) {
            // Idempotent delete/cancel: treat missing request as already removed.
            return;
        }
        if (request.getStatus() == ReservationRequest.Status.DRAFT) {
            deleteReservationsAndAllocations(requestId);
            requestRepo.deleteById(requestId);
            return;
        }

        if (request.getStatus() == ReservationRequest.Status.PENDING_PAYMENT) {
            cancelPaymentForRequest(requestId);
            return;
        }

        if (request.getStatus() == ReservationRequest.Status.CANCELLED) {
            // Idempotent cancel: already canceled.
            return;
        }

        throw new IllegalStateException("Cannot delete/cancel reservation request in status " + request.getStatus());
    }

    private void cancelPaymentAttempt(ReservationRequest request) {
        Long requestId = request.getId();
        var paymentIntents = paymentIntentRepo.findByReservationRequestId(requestId);
        boolean hasPaidIntent = paymentIntents.stream()
                .anyMatch(i -> "PAID".equalsIgnoreCase(i.getStatus()));
        if (hasPaidIntent) {
            throw new IllegalStateException("Cannot cancel request with successful payments");
        }

        OffsetDateTime now = OffsetDateTime.now();
        boolean intentsChanged = false;
        for (var intent : paymentIntents) {
            String status = intent.getStatus() == null ? "" : intent.getStatus().trim().toUpperCase();
            if ("CREATED".equals(status) || "PENDING_CUSTOMER".equals(status) || "PROCESSING".equals(status)) {
                intent.setStatus("CANCELED");
                intent.setCompletedAt(now);
                intent.setUpdatedAt(now);
                if (intent.getErrorMessage() == null || intent.getErrorMessage().isBlank()) {
                    intent.setErrorMessage("Canceled by user");
                }
                intentsChanged = true;
            }
        }
        if (intentsChanged) {
            paymentIntentRepo.saveAll(paymentIntents);
        }
        // Re-open request so user can edit/add reservations and initiate payment again.
        request.setStatus(ReservationRequest.Status.DRAFT);
        requestRepo.save(request);
    }

    private void deleteReservationsAndAllocations(Long requestId) {
        List<Reservation> reservations = reservationRepo.findByRequestId(requestId);
        if (reservations.isEmpty()) {
            return;
        }
        List<Long> reservationIds = reservations.stream().map(Reservation::getId).toList();
        allocationRepo.deleteByReservationIdIn(reservationIds);
        reservationRepo.deleteAll(reservations);
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
