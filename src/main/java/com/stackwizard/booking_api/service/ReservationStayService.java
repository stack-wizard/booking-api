package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CheckinReadinessDto;
import com.stackwizard.booking_api.dto.CheckinResultDto;
import com.stackwizard.booking_api.dto.CheckoutReadinessDto;
import com.stackwizard.booking_api.dto.CheckoutResultDto;
import com.stackwizard.booking_api.dto.InvoiceCheckoutGateResult;
import com.stackwizard.booking_api.exception.CheckoutBlockedException;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReservationStayService {

    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final InvoiceService invoiceService;

    public ReservationStayService(ReservationRequestRepository requestRepo,
                                    ReservationRepository reservationRepo,
                                    InvoiceService invoiceService) {
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.invoiceService = invoiceService;
    }

    /**
     * Read-only validation for UI before check-in (does not create invoices or change status).
     */
    @Transactional(readOnly = true)
    public CheckinReadinessDto getCheckinReadiness(Long requestId) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        assertMatchingTenantIfPresent(request);

        List<String> issues = new ArrayList<>();
        if (request.getStatus() == ReservationRequest.Status.CHECKED_IN) {
            issues.add("Reservation request is already checked in");
            return CheckinReadinessDto.builder().eligible(false).issues(issues).build();
        }
        if (request.getStatus() == ReservationRequest.Status.CHECKED_OUT) {
            issues.add("Reservation request is already checked out");
            return CheckinReadinessDto.builder().eligible(false).issues(issues).build();
        }
        if (request.getStatus() == ReservationRequest.Status.CANCELLED
                || request.getStatus() == ReservationRequest.Status.EXPIRED) {
            issues.add("Reservation request cannot be checked in from status " + request.getStatus());
            return CheckinReadinessDto.builder().eligible(false).issues(issues).build();
        }
        if (request.getStatus() != ReservationRequest.Status.FINALIZED) {
            issues.add("Only FINALIZED reservation requests can be checked in (current: " + request.getStatus() + ")");
            return CheckinReadinessDto.builder().eligible(false).issues(issues).build();
        }
        if (isExpired(request.getExpiresAt())) {
            issues.add("Reservation request expired");
            return CheckinReadinessDto.builder().eligible(false).issues(issues).build();
        }

        List<Reservation> reservations = reservationRepo.findByRequestId(requestId);
        if (reservations.isEmpty()) {
            issues.add("Reservation request has no reservations");
            return CheckinReadinessDto.builder().eligible(false).issues(issues).build();
        }
        for (Reservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            if (!"CONFIRMED".equalsIgnoreCase(reservation.getStatus())) {
                issues.add("Non-cancelled reservation " + reservation.getId() + " must be CONFIRMED (found: "
                        + reservation.getStatus() + ")");
            }
        }

        return CheckinReadinessDto.builder().eligible(issues.isEmpty()).issues(issues).build();
    }

    /**
     * Evaluates invoice gates for checkout (refreshes payment status from allocations; same checks as POST check-out).
     */
    @Transactional
    public CheckoutReadinessDto getCheckoutReadiness(Long requestId) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        assertMatchingTenantIfPresent(request);

        if (request.getStatus() == ReservationRequest.Status.CHECKED_OUT) {
            return CheckoutReadinessDto.builder()
                    .ready(false)
                    .blockers(List.of("Reservation request is already checked out"))
                    .warnings(List.of())
                    .build();
        }
        if (request.getStatus() != ReservationRequest.Status.CHECKED_IN) {
            return CheckoutReadinessDto.builder()
                    .ready(false)
                    .blockers(List.of(
                            "Reservation request must be CHECKED_IN to evaluate checkout (current: "
                                    + request.getStatus() + ")"))
                    .warnings(List.of())
                    .build();
        }

        InvoiceCheckoutGateResult gate = invoiceService.evaluateCheckoutGateForReservationRequest(requestId);
        return CheckoutReadinessDto.builder()
                .ready(!gate.hasBlockers())
                .blockers(gate.blockers())
                .warnings(gate.warnings() != null ? gate.warnings() : List.of())
                .build();
    }

    @Transactional
    public CheckinResultDto checkIn(Long requestId) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        assertMatchingTenantIfPresent(request);

        if (request.getStatus() == ReservationRequest.Status.CHECKED_IN) {
            return buildCheckinResult(requestId);
        }
        if (request.getStatus() == ReservationRequest.Status.CHECKED_OUT) {
            throw new IllegalStateException("Reservation request is already checked out");
        }
        if (request.getStatus() == ReservationRequest.Status.CANCELLED
                || request.getStatus() == ReservationRequest.Status.EXPIRED) {
            throw new IllegalStateException("Reservation request cannot be checked in from status " + request.getStatus());
        }
        if (request.getStatus() != ReservationRequest.Status.FINALIZED) {
            throw new IllegalStateException("Only FINALIZED reservation requests can be checked in");
        }
        if (isExpired(request.getExpiresAt())) {
            throw new IllegalStateException("Reservation request expired");
        }

        List<Reservation> reservations = reservationRepo.findByRequestId(requestId);
        if (reservations.isEmpty()) {
            throw new IllegalStateException("Reservation request has no reservations");
        }
        for (Reservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            if (!"CONFIRMED".equalsIgnoreCase(reservation.getStatus())) {
                throw new IllegalStateException(
                        "All non-cancelled reservations must be CONFIRMED before check-in (found: "
                                + reservation.getStatus() + ")");
            }
        }

        for (Invoice inv : invoiceService.findByRequestId(requestId)) {
            if (inv.getInvoiceType() == InvoiceType.DEPOSIT && inv.getStornoId() == null) {
                if (!invoiceService.hasReversalChildForSourceInvoice(inv.getId(), InvoiceType.DEPOSIT)) {
                    invoiceService.createStornoInvoice(inv.getId());
                }
            }
        }

        invoiceService.createDraftForFinalizedRequest(requestId);
        invoiceService.allocateReleasedDepositPaymentsToFinalRequestInvoice(requestId);

        request.setStatus(ReservationRequest.Status.CHECKED_IN);
        requestRepo.save(request);

        for (Reservation reservation : reservations) {
            if (!"CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                reservation.setStatus("CHECKED_IN");
            }
        }
        reservationRepo.saveAll(reservations);

        return buildCheckinResult(requestId);
    }

    @Transactional
    public CheckoutResultDto checkOut(Long requestId) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        assertMatchingTenantIfPresent(request);

        if (request.getStatus() == ReservationRequest.Status.CHECKED_OUT) {
            return CheckoutResultDto.builder()
                    .status(ReservationRequest.Status.CHECKED_OUT.name())
                    .warnings(List.of())
                    .build();
        }
        if (request.getStatus() != ReservationRequest.Status.CHECKED_IN) {
            throw new IllegalStateException("Only CHECKED_IN reservation requests can be checked out");
        }

        InvoiceCheckoutGateResult gate = invoiceService.evaluateCheckoutGateForReservationRequest(requestId);
        if (gate.hasBlockers()) {
            throw new CheckoutBlockedException(gate.blockers());
        }

        request.setStatus(ReservationRequest.Status.CHECKED_OUT);
        requestRepo.save(request);

        List<Reservation> reservations = reservationRepo.findByRequestId(requestId);
        for (Reservation reservation : reservations) {
            if (!"CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                reservation.setStatus("CHECKED_OUT");
            }
        }
        reservationRepo.saveAll(reservations);

        return CheckoutResultDto.builder()
                .status(ReservationRequest.Status.CHECKED_OUT.name())
                .warnings(gate.warnings() != null ? gate.warnings() : List.of())
                .build();
    }

    private CheckinResultDto buildCheckinResult(Long requestId) {
        return invoiceService.findPrimaryInvoiceForReservationRequest(requestId)
                .map(inv -> CheckinResultDto.builder().finalInvoiceId(inv.getId()).build())
                .orElseGet(() -> CheckinResultDto.builder().finalInvoiceId(null).build());
    }

    private void assertMatchingTenantIfPresent(ReservationRequest request) {
        Long tokenTenantId = TenantContext.getTenantId();
        if (tokenTenantId != null && !tokenTenantId.equals(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId does not match token tenant");
        }
    }

    private static boolean isExpired(OffsetDateTime expiresAt) {
        return expiresAt != null && expiresAt.isBefore(OffsetDateTime.now());
    }
}
