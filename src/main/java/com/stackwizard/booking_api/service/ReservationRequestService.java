package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ReservationRequestSearchCriteria;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.ReservationRequestAccessToken;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.repository.specification.ReservationRequestSpecifications;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReservationRequestService {
    private static final Set<String> REQUEST_STATUS_VALUES = Arrays.stream(ReservationRequest.Status.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> REQUEST_TYPE_VALUES = Arrays.stream(ReservationRequest.Type.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> RESERVATION_STATUS_VALUES = Set.of("HOLD", "CONFIRMED", "CANCELLED");
    private static final Set<String> PAYMENT_INTENT_STATUS_VALUES = Set.of(
            "CREATED", "PENDING_CUSTOMER", "PROCESSING", "PAID", "FAILED", "CANCELED", "EXPIRED", "SUPERSEDED"
    );

    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final AllocationRepository allocationRepo;
    private final PaymentIntentRepository paymentIntentRepo;
    private final ReservationRequestAccessTokenService accessTokenService;
    private final ReservationService reservationService;

    public ReservationRequestService(ReservationRequestRepository requestRepo,
                                     ReservationRepository reservationRepo,
                                     AllocationRepository allocationRepo,
                                     PaymentIntentRepository paymentIntentRepo,
                                     ReservationRequestAccessTokenService accessTokenService,
                                     ReservationService reservationService) {
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.allocationRepo = allocationRepo;
        this.paymentIntentRepo = paymentIntentRepo;
        this.accessTokenService = accessTokenService;
        this.reservationService = reservationService;
    }

    public List<ReservationRequest> findAll() { return requestRepo.findAll(); }
    public Optional<ReservationRequest> findById(Long id) { return requestRepo.findById(id); }
    public ReservationRequest save(ReservationRequest request) { return requestRepo.save(request); }

    @Transactional(readOnly = true)
    public Page<ReservationRequest> search(ReservationRequestSearchCriteria criteria, Pageable pageable) {
        ReservationRequestSearchCriteria normalized = normalizeAndValidate(criteria);
        return requestRepo.findAll(ReservationRequestSpecifications.byCriteria(normalized), pageable);
    }

    @Transactional(readOnly = true)
    public List<ReservationRequest> searchAll(ReservationRequestSearchCriteria criteria, Sort sort) {
        ReservationRequestSearchCriteria normalized = normalizeAndValidate(criteria);
        Sort effectiveSort = (sort == null || sort.isUnsorted()) ? Sort.by(Sort.Direction.DESC, "createdAt") : sort;
        return requestRepo.findAll(ReservationRequestSpecifications.byCriteria(normalized), effectiveSort);
    }

    @Transactional
    public ReservationRequest findByPublicAccessToken(String token) {
        ReservationRequestAccessToken accessToken = accessTokenService.requireValidToken(token);
        ReservationRequest request = requestRepo.findById(accessToken.getReservationRequestId())
                .orElseThrow(() -> new IllegalArgumentException("Reservation request not found"));
        if (request.getStatus() != ReservationRequest.Status.FINALIZED
                && request.getStatus() != ReservationRequest.Status.CANCELLED) {
            throw new IllegalStateException("Reservation request is not available for public access");
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
        var paymentIntents = paymentIntentRepo.findLockedByReservationRequestId(requestId);
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
        reservationService.refreshDraftRequestExpiry(requestId);
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

    private ReservationRequestSearchCriteria normalizeAndValidate(ReservationRequestSearchCriteria criteria) {
        ReservationRequestSearchCriteria resolved = criteria != null ? criteria : new ReservationRequestSearchCriteria();

        resolved.setTenantId(TenantResolver.resolveTenantId(resolved.getTenantId()));

        resolved.setConfirmationNumber(normalizeNullable(resolved.getConfirmationNumber()));
        resolved.setCustomer(normalizeNullable(resolved.getCustomer()));
        resolved.setCustomerName(normalizeNullable(resolved.getCustomerName()));
        resolved.setCustomerEmail(normalizeNullable(resolved.getCustomerEmail()));
        resolved.setCustomerPhone(normalizeNullable(resolved.getCustomerPhone()));
        resolved.setProductName(normalizeNullable(resolved.getProductName()));
        resolved.setResourceName(normalizeNullable(resolved.getResourceName()));

        resolved.setStatuses(normalizeValues(resolved.getStatuses(), REQUEST_STATUS_VALUES, "statuses"));
        resolved.setTypes(normalizeValues(resolved.getTypes(), REQUEST_TYPE_VALUES, "types"));
        resolved.setReservationStatuses(normalizeValues(resolved.getReservationStatuses(), RESERVATION_STATUS_VALUES, "reservationStatuses"));
        resolved.setPaymentIntentStatuses(normalizePaymentIntentStatuses(resolved.getPaymentIntentStatuses()));

        validateRange("createdAt", resolved.getCreatedFrom(), resolved.getCreatedTo());
        validateRange("expiresAt", resolved.getExpiresFrom(), resolved.getExpiresTo());
        validateRange("confirmedAt", resolved.getConfirmedFrom(), resolved.getConfirmedTo());
        validateRange("reservationPeriod", resolved.getReservationFrom(), resolved.getReservationTo());
        validateRange("reservationStartsAt", resolved.getReservationStartsFrom(), resolved.getReservationStartsTo());
        validateRange("reservationEndsAt", resolved.getReservationEndsFrom(), resolved.getReservationEndsTo());

        return resolved;
    }

    private List<String> normalizeValues(List<String> values, Set<String> allowed, String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .filter(StringUtils::hasText)
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        for (String value : normalized) {
            if (!allowed.contains(value)) {
                throw new IllegalArgumentException("Unsupported " + fieldName + " value: " + value);
            }
        }
        return normalized;
    }

    private List<String> normalizePaymentIntentStatuses(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .filter(StringUtils::hasText)
                .map(v -> normalizePaymentIntentStatus(v.trim().toUpperCase(Locale.ROOT)))
                .distinct()
                .toList();
        for (String value : normalized) {
            if (!PAYMENT_INTENT_STATUS_VALUES.contains(value)) {
                throw new IllegalArgumentException("Unsupported paymentIntentStatuses value: " + value);
            }
        }
        return normalized;
    }

    private String normalizePaymentIntentStatus(String status) {
        if ("CANCELLED".equals(status)) {
            return "CANCELED";
        }
        return status;
    }

    private <T extends Comparable<? super T>> void validateRange(String fieldName, T from, T to) {
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw new IllegalArgumentException(fieldName + " range is invalid: from must be <= to");
        }
    }
}
