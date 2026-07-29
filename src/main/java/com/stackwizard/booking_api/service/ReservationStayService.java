package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CheckinReadinessDto;
import com.stackwizard.booking_api.dto.CheckinResultDto;
import com.stackwizard.booking_api.dto.CheckoutReadinessDto;
import com.stackwizard.booking_api.dto.CheckoutResultDto;
import com.stackwizard.booking_api.dto.InvoiceCheckoutGateResult;
import com.stackwizard.booking_api.dto.OperaInvoicePostRequest;
import com.stackwizard.booking_api.exception.CheckoutBlockedException;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.config.BookingOperaProperties;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.security.TenantContext;
import com.stackwizard.booking_api.service.fiscal.InvoiceAutoFiscalizationRequestedEvent;
import com.stackwizard.booking_api.service.fiscal.OperaFiscalMappingService;
import com.stackwizard.booking_api.service.opera.OperaCheckInOrchestrator;
import com.stackwizard.booking_api.service.opera.OperaInvoicePostingService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReservationStayService {

    private static final Logger log = LoggerFactory.getLogger(ReservationStayService.class);

    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final InvoiceService invoiceService;
    private final BookingOperaProperties bookingOperaProperties;
    private final OperaCheckInOrchestrator operaCheckInOrchestrator;
    private final OperaInvoicePostingService operaInvoicePostingService;
    private final ProductRepository productRepo;
    private final OperaFiscalMappingService operaFiscalMappingService;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public ReservationStayService(ReservationRequestRepository requestRepo,
                                    ReservationRepository reservationRepo,
                                    InvoiceService invoiceService,
                                    BookingOperaProperties bookingOperaProperties,
                                    OperaCheckInOrchestrator operaCheckInOrchestrator,
                                    OperaInvoicePostingService operaInvoicePostingService,
                                    ProductRepository productRepo,
                                    OperaFiscalMappingService operaFiscalMappingService,
                                    EntityManager entityManager,
                                    ApplicationEventPublisher eventPublisher) {
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.invoiceService = invoiceService;
        this.bookingOperaProperties = bookingOperaProperties;
        this.operaCheckInOrchestrator = operaCheckInOrchestrator;
        this.operaInvoicePostingService = operaInvoicePostingService;
        this.productRepo = productRepo;
        this.operaFiscalMappingService = operaFiscalMappingService;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Read-only validation for UI before check-in (does not create invoices or change status).
     */
    @Transactional(readOnly = true)
    public CheckinReadinessDto getCheckinReadiness(Long requestId) {
        return getCheckinReadiness(requestId, false);
    }

    @Transactional(readOnly = true)
    public CheckinReadinessDto getCheckinReadiness(Long requestId, boolean skipOperaCheckIn) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        assertMatchingTenantIfPresent(request);

        List<Reservation> reservations = reservationRepo.findByRequestIdWithDetails(requestId);
        LineBuckets buckets = partitionLines(reservations, LocalDate.now());
        boolean operaSkippable = isOperaCheckInSkippable(request);

        List<String> issues = new ArrayList<>();
        if (request.getStatus() == ReservationRequest.Status.CHECKED_IN) {
            issues.add("Reservation request is already checked in");
            return readiness(false, issues, buckets, operaSkippable);
        }
        if (request.getStatus() == ReservationRequest.Status.CHECKED_OUT) {
            issues.add("Reservation request is already checked out");
            return readiness(false, issues, buckets, operaSkippable);
        }
        if (request.getStatus() == ReservationRequest.Status.CANCELLED
                || request.getStatus() == ReservationRequest.Status.EXPIRED) {
            issues.add("Reservation request cannot be checked in from status " + request.getStatus());
            return readiness(false, issues, buckets, operaSkippable);
        }
        if (request.getStatus() != ReservationRequest.Status.FINALIZED
                && request.getStatus() != ReservationRequest.Status.PARTIALLY_CHECKED_IN) {
            issues.add("Only FINALIZED or PARTIALLY_CHECKED_IN reservation requests can be checked in (current: "
                    + request.getStatus() + ")");
            return readiness(false, issues, buckets, operaSkippable);
        }
        if (isExpired(request.getExpiresAt())) {
            issues.add("Reservation request expired");
            return readiness(false, issues, buckets, operaSkippable);
        }

        if (reservations.isEmpty()) {
            issues.add("Reservation request has no reservations");
            return readiness(false, issues, buckets, operaSkippable);
        }
        for (Reservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            if (!"CONFIRMED".equalsIgnoreCase(reservation.getStatus())
                    && !"CHECKED_IN".equalsIgnoreCase(reservation.getStatus())) {
                issues.add("Non-cancelled reservation " + reservation.getId() + " must be CONFIRMED or CHECKED_IN (found: "
                        + reservation.getStatus() + ")");
            }
        }
        if (buckets.dueToday().isEmpty()) {
            if (buckets.future().isEmpty()) {
                issues.add("No confirmed reservation lines remaining to check in");
            } else {
                issues.add("No reservation lines are due for check-in today; future lines remain for later dates");
            }
        }
        if (!skipOperaCheckIn && bookingOperaProperties.getCheckIn().isEnabled()) {
            if (requiresOperaCheckIn(request)) {
                for (Reservation reservation : buckets.dueToday()) {
                    if (reservation.getRequestedResource() == null
                            || !StringUtils.hasText(reservation.getRequestedResource().getOperaRoomId())) {
                        issues.add("Opera check-in requires OHIP room id on resource for reservation line "
                                + reservation.getId());
                    }
                }
            }
            addLinkedOperaReservationIssues(request, issues);
            addOperaChargeMappingIssues(request.getTenantId(), buckets.dueToday(), issues);
        }

        return readiness(issues.isEmpty(), issues, buckets, operaSkippable);
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
        return checkIn(requestId, false, null);
    }

    @Transactional
    public CheckinResultDto checkIn(Long requestId, boolean skipOperaCheckIn) {
        return checkIn(requestId, skipOperaCheckIn, null);
    }

    @Transactional
    public CheckinResultDto checkIn(Long requestId, boolean skipOperaCheckIn, Long finalInvoiceOperaReservationId) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        assertMatchingTenantIfPresent(request);

        if (request.getStatus() == ReservationRequest.Status.CHECKED_IN) {
            List<Reservation> reservations = reservationRepo.findByRequestId(requestId);
            return buildCheckinResult(requestId, request.getStatus(), reservations);
        }
        if (request.getStatus() == ReservationRequest.Status.CHECKED_OUT) {
            throw new IllegalStateException("Reservation request is already checked out");
        }
        if (request.getStatus() == ReservationRequest.Status.CANCELLED
                || request.getStatus() == ReservationRequest.Status.EXPIRED) {
            throw new IllegalStateException("Reservation request cannot be checked in from status " + request.getStatus());
        }
        if (request.getStatus() != ReservationRequest.Status.FINALIZED
                && request.getStatus() != ReservationRequest.Status.PARTIALLY_CHECKED_IN) {
            throw new IllegalStateException(
                    "Only FINALIZED or PARTIALLY_CHECKED_IN reservation requests can be checked in");
        }
        if (isExpired(request.getExpiresAt())) {
            throw new IllegalStateException("Reservation request expired");
        }

        ReservationRequest.Status previousStatus = request.getStatus();
        Long manualOperaReservationId = normalizePositiveLong(finalInvoiceOperaReservationId);
        // Repair path: skip create/check-in on closed OHIP stays; still post deposit + final to manual id.
        boolean skipOperaOrchestrator = skipOperaCheckIn || manualOperaReservationId != null;
        boolean postOperaMoneyToManualTarget = manualOperaReservationId != null;
        boolean requireChargeMappings = (!skipOperaOrchestrator || postOperaMoneyToManualTarget)
                && bookingOperaProperties.getCheckIn().isEnabled();

        List<Reservation> reservations = reservationRepo.findByRequestIdWithDetails(requestId);
        if (reservations.isEmpty()) {
            throw new IllegalStateException("Reservation request has no reservations");
        }
        for (Reservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            if (!"CONFIRMED".equalsIgnoreCase(reservation.getStatus())
                    && !"CHECKED_IN".equalsIgnoreCase(reservation.getStatus())) {
                throw new IllegalStateException(
                        "All non-cancelled reservations must be CONFIRMED or CHECKED_IN before check-in (found: "
                                + reservation.getStatus() + ")");
            }
        }

        LineBuckets buckets = partitionLines(reservations, LocalDate.now());
        if (buckets.dueToday().isEmpty()) {
            if (buckets.future().isEmpty()) {
                throw new IllegalStateException("No confirmed reservation lines remaining to check in");
            }
            throw new IllegalStateException(
                    "No reservation lines are due for check-in today; future lines remain for later dates");
        }

        if (requireChargeMappings) {
            List<String> chargeMappingBlockers = new ArrayList<>();
            List<Reservation> mappingScope = postOperaMoneyToManualTarget ? reservations : buckets.dueToday();
            addOperaChargeMappingIssues(request.getTenantId(), mappingScope, chargeMappingBlockers);
            if (!chargeMappingBlockers.isEmpty()) {
                throw new IllegalStateException(String.join("; ", chargeMappingBlockers));
            }
        }

        if (postOperaMoneyToManualTarget) {
            operaCheckInOrchestrator.postDepositPaymentIfNeeded(
                    request, manualOperaReservationId, resolveCurrency(reservations));
        } else if (!skipOperaOrchestrator) {
            if (requiresLinkedOperaReservation(request)) {
                propagateLinkedOperaReservation(buckets.dueToday(), request);
            } else {
                operaCheckInOrchestrator.runIfEnabled(request, buckets.dueToday());
            }

            if (bookingOperaProperties.getCheckIn().isEnabled() && requiresOperaCheckIn(request)) {
                request = requestRepo.findById(requestId)
                        .orElseThrow(() -> new IllegalArgumentException("Request not found"));
                reservations = reservationRepo.findByRequestIdWithDetails(requestId);
                // Opera progress is persisted in REQUIRES_NEW transactions; same check-in transaction may still hold
                // stale Reservation entities. Reload OHIP ids from DB before final invoice + posting.
                for (Reservation r : reservations) {
                    entityManager.refresh(r);
                }
                buckets = partitionLines(reservations, LocalDate.now());
            }
        }

        if (previousStatus == ReservationRequest.Status.FINALIZED) {
            for (Invoice inv : invoiceService.findByRequestId(requestId)) {
                if (inv.getInvoiceType() == InvoiceType.DEPOSIT && inv.getStornoId() == null) {
                    if (!invoiceService.hasReversalChildForSourceInvoice(inv.getId(), InvoiceType.DEPOSIT)) {
                        Invoice stornoInvoice = invoiceService.createStornoInvoice(inv.getId());
                        publishAutoFiscalizationIfRequired(stornoInvoice);
                    }
                }
            }

            invoiceService.createDraftForFinalizedRequest(requestId);
            invoiceService.allocateReleasedDepositPaymentsToFinalRequestInvoice(requestId);
            Invoice finalInvoice = invoiceService.issueSystemFinalInvoiceForRequest(requestId);
            if (postOperaMoneyToManualTarget && shouldPostFinalInvoiceToOpera(request)) {
                OperaInvoicePostRequest postRequest = new OperaInvoicePostRequest();
                postRequest.setReservationId(manualOperaReservationId);
                try {
                    operaInvoicePostingService.postInvoice(finalInvoice.getId(), postRequest);
                } catch (RuntimeException ex) {
                    // Soft-fail: local check-in + issued invoice must not roll back if OHIP post fails.
                    log.warn("Opera final invoice post to manual reservation {} failed for request {}",
                            manualOperaReservationId, requestId, ex);
                }
            } else if (!skipOperaCheckIn && !postOperaMoneyToManualTarget && shouldPostFinalInvoiceToOpera(request)) {
                // Soft-fail: local check-in + issued invoice must not roll back if OHIP post fails.
                operaInvoicePostingService.tryAutoPostInvoice(finalInvoice.getId());
            }
        }

        for (Reservation reservation : buckets.dueToday()) {
            reservation.setStatus("CHECKED_IN");
        }
        reservationRepo.saveAll(buckets.dueToday());

        // Re-evaluate after local status updates (dueToday are now CHECKED_IN).
        reservations = reservationRepo.findByRequestIdWithDetails(requestId);
        ReservationRequest.Status nextStatus = resolveRequestStatusAfterCheckIn(reservations);
        request.setStatus(nextStatus);
        requestRepo.save(request);

        return buildCheckinResult(requestId, nextStatus, reservations);
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

    static LineBuckets partitionLines(List<Reservation> reservations, LocalDate today) {
        List<Reservation> dueToday = new ArrayList<>();
        List<Reservation> alreadyCheckedIn = new ArrayList<>();
        List<Reservation> future = new ArrayList<>();
        if (reservations == null) {
            return new LineBuckets(dueToday, alreadyCheckedIn, future);
        }
        for (Reservation reservation : reservations) {
            if (reservation == null || "CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            if ("CHECKED_IN".equalsIgnoreCase(reservation.getStatus())
                    || "CHECKED_OUT".equalsIgnoreCase(reservation.getStatus())) {
                alreadyCheckedIn.add(reservation);
                continue;
            }
            if (!"CONFIRMED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            LocalDate serviceDate = reservation.getStartsAt() != null
                    ? reservation.getStartsAt().toLocalDate()
                    : null;
            if (serviceDate == null || !serviceDate.isAfter(today)) {
                dueToday.add(reservation);
            } else {
                future.add(reservation);
            }
        }
        return new LineBuckets(dueToday, alreadyCheckedIn, future);
    }

    static ReservationRequest.Status resolveRequestStatusAfterCheckIn(List<Reservation> reservations) {
        boolean hasRemainingConfirmed = false;
        boolean hasCheckedIn = false;
        if (reservations != null) {
            for (Reservation reservation : reservations) {
                if (reservation == null || "CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                    continue;
                }
                if ("CONFIRMED".equalsIgnoreCase(reservation.getStatus())) {
                    hasRemainingConfirmed = true;
                } else if ("CHECKED_IN".equalsIgnoreCase(reservation.getStatus())) {
                    hasCheckedIn = true;
                }
            }
        }
        if (hasRemainingConfirmed && hasCheckedIn) {
            return ReservationRequest.Status.PARTIALLY_CHECKED_IN;
        }
        return ReservationRequest.Status.CHECKED_IN;
    }

    private void addOperaChargeMappingIssues(Long tenantId, List<Reservation> reservations, List<String> issues) {
        if (tenantId == null || reservations == null) {
            return;
        }
        for (Reservation r : reservations) {
            if (r.getStatus() != null && "CANCELLED".equalsIgnoreCase(r.getStatus().trim())) {
                continue;
            }
            Long productId = r.getProductId();
            String productType = null;
            if (productId != null) {
                productType = productRepo.findById(productId)
                        .map(Product::getProductType)
                        .orElse(null);
            }
            if (operaFiscalMappingService.resolveChargeMapping(tenantId, productId, productType).isEmpty()) {
                String typeLabel = productType != null ? productType : "unknown";
                issues.add("Opera charge mapping is missing for reservation line " + r.getId()
                        + " (productId=" + productId + ", productType=" + typeLabel
                        + "). Configure an active opera_fiscal_charge_mapping for this product, for product_type "
                        + typeLabel + ", or a tenant default row with product_id and product_type both null.");
            }
        }
    }

    private void addLinkedOperaReservationIssues(ReservationRequest request, List<String> issues) {
        if (request == null || !requiresLinkedOperaReservation(request)) {
            return;
        }
        if (!StringUtils.hasText(request.getOperaHotelCode())) {
            issues.add("Opera hotel is required for linked in-house posting");
        }
        if (request.getLinkedOperaReservationId() == null || request.getLinkedOperaReservationId() <= 0) {
            issues.add("Linked Opera reservation id is required for in-house posting");
        }
    }

    private boolean isOperaCheckInSkippable(ReservationRequest request) {
        return bookingOperaProperties.getCheckIn().isEnabled()
                && request != null
                && request.getType() != ReservationRequest.Type.INTERNAL;
    }

    private boolean requiresOperaCheckIn(ReservationRequest request) {
        return request != null
                && request.getType() != ReservationRequest.Type.INHOUSE
                && request.getLinkedOperaReservationId() == null;
    }

    private boolean requiresLinkedOperaReservation(ReservationRequest request) {
        return request != null && request.getType() == ReservationRequest.Type.INHOUSE
                || request != null && request.getLinkedOperaReservationId() != null;
    }

    private boolean shouldPostFinalInvoiceToOpera(ReservationRequest request) {
        return request != null
                && bookingOperaProperties.getCheckIn().isEnabled()
                && request.getType() != ReservationRequest.Type.INTERNAL;
    }

    private void propagateLinkedOperaReservation(List<Reservation> reservations, ReservationRequest request) {
        if (request == null || request.getLinkedOperaReservationId() == null || reservations == null || reservations.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Reservation reservation : reservations) {
            if ("CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                continue;
            }
            if (!request.getLinkedOperaReservationId().equals(reservation.getOperaReservationId())) {
                reservation.setOperaReservationId(request.getLinkedOperaReservationId());
                changed = true;
            }
        }
        if (changed) {
            reservationRepo.saveAll(reservations);
        }
    }

    private CheckinResultDto buildCheckinResult(Long requestId,
                                                ReservationRequest.Status requestStatus,
                                                List<Reservation> reservations) {
        List<Long> checkedInIds = new ArrayList<>();
        List<Long> remainingIds = new ArrayList<>();
        if (reservations != null) {
            for (Reservation reservation : reservations) {
                if (reservation == null || "CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                    continue;
                }
                if ("CHECKED_IN".equalsIgnoreCase(reservation.getStatus())
                        || "CHECKED_OUT".equalsIgnoreCase(reservation.getStatus())) {
                    checkedInIds.add(reservation.getId());
                } else if ("CONFIRMED".equalsIgnoreCase(reservation.getStatus())) {
                    remainingIds.add(reservation.getId());
                }
            }
        }
        Long finalInvoiceId = invoiceService.findPrimaryInvoiceForReservationRequest(requestId)
                .map(Invoice::getId)
                .orElse(null);
        return CheckinResultDto.builder()
                .finalInvoiceId(finalInvoiceId)
                .requestStatus(requestStatus != null ? requestStatus.name() : null)
                .checkedInReservationCount(checkedInIds.size())
                .remainingConfirmedReservationCount(remainingIds.size())
                .checkedInReservationIds(checkedInIds)
                .remainingConfirmedReservationIds(remainingIds)
                .build();
    }

    private static CheckinReadinessDto readiness(boolean eligible,
                                                 List<String> issues,
                                                 LineBuckets buckets,
                                                 boolean operaCheckInSkippable) {
        return CheckinReadinessDto.builder()
                .eligible(eligible)
                .issues(issues)
                .operaCheckInSkippable(operaCheckInSkippable)
                .dueTodayReservationIds(idsOf(buckets.dueToday()))
                .alreadyCheckedInReservationIds(idsOf(buckets.alreadyCheckedIn()))
                .futureReservationIds(idsOf(buckets.future()))
                .build();
    }

    private static List<Long> idsOf(List<Reservation> reservations) {
        List<Long> ids = new ArrayList<>();
        for (Reservation reservation : reservations) {
            if (reservation != null && reservation.getId() != null) {
                ids.add(reservation.getId());
            }
        }
        return ids;
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

    private void publishAutoFiscalizationIfRequired(Invoice invoice) {
        if (invoice != null
                && invoice.getId() != null
                && invoice.getFiscalizationStatus() != null
                && invoice.getFiscalizationStatus().name().equals("REQUIRED")) {
            eventPublisher.publishEvent(new InvoiceAutoFiscalizationRequestedEvent(invoice.getId()));
        }
    }

    private static Long normalizePositiveLong(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private static String resolveCurrency(List<Reservation> stays) {
        if (stays != null) {
            for (Reservation stay : stays) {
                if (stay != null && StringUtils.hasText(stay.getCurrency())) {
                    return stay.getCurrency().trim();
                }
            }
        }
        return "EUR";
    }

    record LineBuckets(List<Reservation> dueToday,
                       List<Reservation> alreadyCheckedIn,
                       List<Reservation> future) {
    }
}
