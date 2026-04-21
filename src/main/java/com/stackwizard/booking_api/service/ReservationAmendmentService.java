package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.ReservationRequestAmendmentApplyResponse;
import com.stackwizard.booking_api.dto.ReservationRequestAmendmentMutateRequest;
import com.stackwizard.booking_api.dto.ReservationRequestAmendmentPreviewResponse;
import com.stackwizard.booking_api.dto.ReservationRequestAmendmentReplacementDto;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.ReservationRequestAmendment;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestAmendmentRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReservationAmendmentService {

    public static final String AMENDMENT_STATUS_APPLIED = "APPLIED";

    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final AllocationRepository allocationRepo;
    private final ResourceRepository resourceRepo;
    private final ProductRepository productRepo;
    private final ResourceCompositionRepository compositionRepo;
    private final ReservationRequestAmendmentRepository amendmentRepo;
    /** Same pattern as {@link PaymentService}: no Jackson {@code ObjectMapper} bean in this app. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReservationAmendmentService(
            ReservationRequestRepository requestRepo,
            ReservationRepository reservationRepo,
            AllocationRepository allocationRepo,
            ResourceRepository resourceRepo,
            ProductRepository productRepo,
            ResourceCompositionRepository compositionRepo,
            ReservationRequestAmendmentRepository amendmentRepo) {
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.allocationRepo = allocationRepo;
        this.resourceRepo = resourceRepo;
        this.productRepo = productRepo;
        this.compositionRepo = compositionRepo;
        this.amendmentRepo = amendmentRepo;
    }

    @Transactional(readOnly = true)
    public ReservationRequestAmendmentPreviewResponse preview(
            Long tenantId, Long reservationRequestId, ReservationRequestAmendmentMutateRequest body) {
        ReservationRequest request = loadEligibleRequest(tenantId, reservationRequestId);
        validateReplacementsUnique(body);
        Set<Long> excludeReservationIds = body.getReplacements().stream()
                .map(ReservationRequestAmendmentReplacementDto::getCancelReservationId)
                .collect(Collectors.toSet());

        List<String> messages = new ArrayList<>();
        BigDecimal oldGross = BigDecimal.ZERO;
        BigDecimal newGross = BigDecimal.ZERO;

        for (ReservationRequestAmendmentReplacementDto rep : body.getReplacements()) {
            Reservation old = loadCancellableLine(request, rep.getCancelReservationId());
            validateAmendableAllocations(old);
            Resource newRequested = loadResource(tenantId, rep.getNewRequestedResourceId());
            Product product = loadProduct(old.getProductId());
            validateProductResource(product, newRequested);

            LocalDateTime[] window = resolveStayWindow(old, rep.getNewStayStartDate());
            ensureAvailabilityForNewResource(newRequested, window[0], window[1], excludeReservationIds);

            oldGross = oldGross.add(nz(old.getGrossAmount()));
            newGross = newGross.add(nz(old.getGrossAmount()));
            messages.add("OK reservation " + old.getId() + " -> resource " + newRequested.getId()
                    + " window " + window[0] + ".." + window[1]);
        }

        return ReservationRequestAmendmentPreviewResponse.builder()
                .ok(true)
                .messages(messages)
                .grossDelta(newGross.subtract(oldGross))
                .build();
    }

    @Transactional
    public ReservationRequestAmendmentApplyResponse apply(
            Long tenantId, Long reservationRequestId, ReservationRequestAmendmentMutateRequest body) {
        ReservationRequest request = loadEligibleRequest(tenantId, reservationRequestId);
        validateReplacementsUnique(body);
        Set<Long> excludeReservationIds = body.getReplacements().stream()
                .map(ReservationRequestAmendmentReplacementDto::getCancelReservationId)
                .collect(Collectors.toSet());

        for (ReservationRequestAmendmentReplacementDto rep : body.getReplacements()) {
            Reservation old = loadCancellableLine(request, rep.getCancelReservationId());
            validateAmendableAllocations(old);
            Resource newRequested = loadResource(tenantId, rep.getNewRequestedResourceId());
            Product product = loadProduct(old.getProductId());
            validateProductResource(product, newRequested);
            LocalDateTime[] window = resolveStayWindow(old, rep.getNewStayStartDate());
            ensureAvailabilityForNewResource(newRequested, window[0], window[1], excludeReservationIds);
        }

        for (ReservationRequestAmendmentReplacementDto rep : body.getReplacements()) {
            cancelReservationLine(rep.getCancelReservationId());
        }

        List<Long> newIds = new ArrayList<>();
        String newLineStatus = request.getStatus() == ReservationRequest.Status.CHECKED_IN ? "CHECKED_IN" : "CONFIRMED";

        for (ReservationRequestAmendmentReplacementDto rep : body.getReplacements()) {
            Reservation old = reservationRepo.findById(rep.getCancelReservationId())
                    .orElseThrow(() -> new IllegalStateException("Reservation disappeared"));
            if (!"CANCELLED".equalsIgnoreCase(old.getStatus())) {
                throw new IllegalStateException("Expected cancelled reservation " + old.getId());
            }
            Resource newRequested = loadResource(tenantId, rep.getNewRequestedResourceId());
            Product product = loadProduct(old.getProductId());
            LocalDateTime[] window = resolveStayWindow(old, rep.getNewStayStartDate());
            Reservation created = createReplacementReservation(request, old, newRequested, newLineStatus, window[0], window[1]);
            reservationRepo.save(created);
            createConfirmedAllocations(created, newRequested, product, old);
            newIds.add(created.getId());
        }

        JsonNode payload = objectMapper.valueToTree(body);
        ReservationRequestAmendment amendment = amendmentRepo.save(ReservationRequestAmendment.builder()
                .tenantId(tenantId)
                .reservationRequestId(reservationRequestId)
                .status(AMENDMENT_STATUS_APPLIED)
                .requestPayload(payload)
                .failureReason(null)
                .build());

        return ReservationRequestAmendmentApplyResponse.builder()
                .amendmentId(amendment.getId())
                .newReservationIds(newIds)
                .build();
    }

    private ReservationRequest loadEligibleRequest(Long tenantId, Long reservationRequestId) {
        ReservationRequest request = requestRepo.findById(reservationRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation request not found"));
        if (!Objects.equals(request.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("Tenant mismatch");
        }
        if (request.getStatus() != ReservationRequest.Status.FINALIZED
                && request.getStatus() != ReservationRequest.Status.CHECKED_IN) {
            throw new IllegalStateException("Amendment allowed only for FINALIZED or CHECKED_IN requests (current: "
                    + request.getStatus() + ")");
        }
        return request;
    }

    private void validateReplacementsUnique(ReservationRequestAmendmentMutateRequest body) {
        Set<Long> seen = new HashSet<>();
        for (ReservationRequestAmendmentReplacementDto r : body.getReplacements()) {
            if (!seen.add(r.getCancelReservationId())) {
                throw new IllegalArgumentException("Duplicate cancelReservationId: " + r.getCancelReservationId());
            }
        }
    }

    private Reservation loadCancellableLine(ReservationRequest request, Long reservationId) {
        Reservation r = reservationRepo.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + reservationId));
        if (r.getRequest() == null || r.getRequest().getId() == null
                || !Objects.equals(r.getRequest().getId(), request.getId())) {
            throw new IllegalArgumentException("Reservation does not belong to this request");
        }
        if ("CANCELLED".equalsIgnoreCase(r.getStatus())) {
            throw new IllegalStateException("Reservation already cancelled: " + reservationId);
        }
        return r;
    }

    /**
     * Amendments cancel all allocation rows for a reservation and recreate them for the new requested resource.
     * Non-composition bookings have a single row; composition bookings have one row per composition member slot.
     */
    private void validateAmendableAllocations(Reservation old) {
        Long reservationId = old.getId();
        List<Allocation> allocs = allocationRepo.findByReservationIdIn(List.of(reservationId));
        if (allocs.isEmpty()) {
            throw new IllegalStateException("Reservation has no allocation rows: " + reservationId);
        }
        if (allocs.size() == 1) {
            return;
        }
        Allocation first = allocs.getFirst();
        if (first.getRequestedResource() == null || first.getRequestedResource().getId() == null) {
            throw new IllegalStateException("Allocation missing requested resource for reservation " + reservationId);
        }
        Long parentRequestedId = first.getRequestedResource().getId();
        for (Allocation a : allocs) {
            if (a.getRequestedResource() == null || a.getRequestedResource().getId() == null) {
                throw new IllegalStateException(
                        "Allocation " + a.getId() + " missing requested resource for reservation " + reservationId);
            }
            if (!parentRequestedId.equals(a.getRequestedResource().getId())) {
                throw new IllegalStateException(
                        "Amendment supports only a single allocation or a uniform composition; reservation "
                                + reservationId + " has allocation rows for different requested resources");
            }
            if (!Boolean.TRUE.equals(a.getCompositResource())) {
                throw new IllegalStateException(
                        "Reservation " + reservationId + " has " + allocs.size()
                                + " allocation rows but allocation " + a.getId()
                                + " is not marked as a composition member (composit_resource)");
            }
            if (!Objects.equals(a.getCompositResourceId(), parentRequestedId)) {
                throw new IllegalStateException(
                        "Reservation " + reservationId + " composition allocations must use composit_resource_id="
                                + parentRequestedId + " (allocation " + a.getId() + ")");
            }
        }
        Resource parent = resourceRepo.findById(parentRequestedId)
                .orElseThrow(() -> new IllegalStateException("Requested resource not found: " + parentRequestedId));
        if (!isCompositionResource(parent)) {
            throw new IllegalStateException(
                    "Reservation " + reservationId + " has " + allocs.size()
                            + " allocation rows but requested resource " + parentRequestedId
                            + " is not a composition resource");
        }
        List<ResourceComposition> members = compositionRepo.findByParentResourceId(parentRequestedId);
        int expectedSlots = 0;
        for (ResourceComposition m : members) {
            int q = m.getQty() != null ? m.getQty() : 1;
            expectedSlots += q;
        }
        if (expectedSlots != allocs.size()) {
            throw new IllegalStateException(
                    "Composition reservation " + reservationId + " has " + allocs.size()
                            + " allocation rows but composition of resource " + parentRequestedId + " defines "
                            + expectedSlots + " member slot(s)");
        }
    }

    private Resource loadResource(Long tenantId, Long resourceId) {
        Resource r = resourceRepo.findById(resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Resource not found: " + resourceId));
        if (!Objects.equals(r.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("Resource tenant mismatch");
        }
        return r;
    }

    private Product loadProduct(Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("Reservation has no productId");
        }
        return productRepo.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
    }

    /**
     * Reservation lines keep the request's product; the replacement resource normally must match that product.
     * Resources flagged {@code can_book_alone} may be booked on another product's line (same pattern as public availability).
     */
    private void validateProductResource(Product product, Resource resource) {
        Long resourceProductId = resource.getProduct() != null && resource.getProduct().getId() != null
                ? resource.getProduct().getId()
                : null;
        if (resourceProductId == null || resourceProductId.equals(product.getId())) {
            return;
        }
        if (Boolean.FALSE.equals(resource.getCanBookAlone())) {
            throw new IllegalArgumentException("Product not applicable to resource");
        }
    }

    private void ensureAvailabilityForNewResource(
            Resource requested, LocalDateTime starts, LocalDateTime ends, Set<Long> excludeReservationIds) {
        List<ResourceComposition> members = compositionRepo.findByParentResourceId(requested.getId());
        boolean isComposition = isCompositionResource(requested);
        if (members.isEmpty()) {
            if (isComposition) {
                throw new IllegalStateException("Composition resource has no members");
            }
            ensureAvailabilityExcluding(requested, starts, ends, excludeReservationIds);
        } else {
            for (ResourceComposition member : members) {
                Resource memberResource = member.getMemberResource();
                int memberQty = member.getQty() != null ? member.getQty() : 1;
                for (int i = 0; i < memberQty; i++) {
                    ensureAvailabilityExcluding(memberResource, starts, ends, excludeReservationIds);
                }
            }
        }
    }

    private boolean isCompositionResource(Resource resource) {
        return resource.getResourceType() != null
                && resource.getResourceType().getCode() != null
                && "COMPOSITION".equalsIgnoreCase(resource.getResourceType().getCode());
    }

    private void ensureAvailabilityExcluding(
            Resource resource, LocalDateTime starts, LocalDateTime ends, Set<Long> excludeReservationIds) {
        List<Allocation> active = allocationRepo.findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(
                List.of(resource.getId()), ends, starts);
        long overlapping = active.stream()
                .filter(a -> a.getReservation() != null
                        && (excludeReservationIds.isEmpty()
                        || !excludeReservationIds.contains(a.getReservation().getId())))
                .count();

        int capacity = resource.getUnitCount() != null ? resource.getUnitCount() : 1;
        if ("POOL".equalsIgnoreCase(resource.getKind())) {
            if (resource.getPoolTotalUnits() == null) {
                throw new IllegalStateException("Pool resource missing poolTotalUnits");
            }
            capacity = resource.getPoolTotalUnits();
        }

        if (overlapping >= capacity) {
            throw new IllegalStateException("No availability for the requested resource in the given time range");
        }
    }

    private void cancelReservationLine(Long reservationId) {
        Reservation r = reservationRepo.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        r.setStatus("CANCELLED");
        r.setExpiresAt(null);
        reservationRepo.save(r);
        List<Allocation> allocs = allocationRepo.findByReservationIdIn(List.of(reservationId));
        for (Allocation a : allocs) {
            a.setStatus("CANCELLED");
            a.setExpiresAt(null);
        }
        allocationRepo.saveAll(allocs);
    }

    private LocalDateTime[] resolveStayWindow(Reservation old, String newStayStartDate) {
        if (newStayStartDate == null || newStayStartDate.isBlank()) {
            return new LocalDateTime[] {old.getStartsAt(), old.getEndsAt()};
        }
        LocalDate newDate;
        try {
            newDate = LocalDate.parse(newStayStartDate.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("newStayStartDate must be YYYY-MM-DD: " + newStayStartDate);
        }
        LocalDateTime oldStart = old.getStartsAt();
        LocalDateTime oldEnd = old.getEndsAt();
        if (oldStart == null || oldEnd == null) {
            throw new IllegalStateException("Reservation missing startsAt/endsAt");
        }
        Duration dur = Duration.between(oldStart, oldEnd);
        if (dur.isNegative() || dur.isZero()) {
            throw new IllegalStateException("Reservation has invalid duration");
        }
        LocalDateTime newStart = LocalDateTime.of(newDate, oldStart.toLocalTime());
        LocalDateTime newEnd = newStart.plus(dur);
        return new LocalDateTime[] {newStart, newEnd};
    }

    private Reservation createReplacementReservation(
            ReservationRequest request,
            Reservation old,
            Resource newRequested,
            String newLineStatus,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
        return Reservation.builder()
                .tenantId(old.getTenantId())
                .productId(old.getProductId())
                .request(request)
                .requestType(request.getType())
                .requestedResource(newRequested)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .status(newLineStatus)
                .expiresAt(null)
                .adults(old.getAdults())
                .children(old.getChildren())
                .infants(old.getInfants())
                .customerName(old.getCustomerName())
                .customerEmail(old.getCustomerEmail())
                .customerPhone(old.getCustomerPhone())
                .cancellationPolicyId(old.getCancellationPolicyId())
                .cancellationPolicyText(old.getCancellationPolicyText())
                .cancellationPolicySnapshot(old.getCancellationPolicySnapshot())
                .currency(old.getCurrency())
                .qty(old.getQty())
                .unitPrice(old.getUnitPrice())
                .grossAmount(old.getGrossAmount())
                .build();
    }

    private void createConfirmedAllocations(Reservation saved, Resource requested, Product product, Reservation old) {
        Allocation template = allocationRepo.findByReservationIdIn(List.of(old.getId())).getFirst();
        String uom = template.getUom();
        Integer qty = template.getQty();

        List<ResourceComposition> members = compositionRepo.findByParentResourceId(requested.getId());
        boolean isComposition = isCompositionResource(requested);
        List<Allocation> allocations = new ArrayList<>();
        if (members.isEmpty()) {
            if (isComposition) {
                throw new IllegalStateException("Composition resource has no members");
            }
            allocations.add(Allocation.builder()
                    .tenantId(saved.getTenantId())
                    .productId(product.getId())
                    .uom(uom)
                    .qty(qty)
                    .reservation(saved)
                    .requestedResource(requested)
                    .allocatedResource(requested)
                    .resourceKind(requested.getKind())
                    .compositResource(false)
                    .compositResourceId(null)
                    .startsAt(saved.getStartsAt())
                    .endsAt(saved.getEndsAt())
                    .status("CONFIRMED")
                    .expiresAt(null)
                    .build());
        } else {
            for (ResourceComposition member : members) {
                Resource memberResource = member.getMemberResource();
                int memberQty = member.getQty() != null ? member.getQty() : 1;
                for (int i = 0; i < memberQty; i++) {
                    allocations.add(Allocation.builder()
                            .tenantId(saved.getTenantId())
                            .productId(product.getId())
                            .uom(uom)
                            .qty(qty)
                            .reservation(saved)
                            .requestedResource(requested)
                            .allocatedResource(memberResource)
                            .resourceKind(memberResource.getKind())
                            .compositResource(true)
                            .compositResourceId(requested.getId())
                            .startsAt(saved.getStartsAt())
                            .endsAt(saved.getEndsAt())
                            .status("CONFIRMED")
                            .expiresAt(null)
                            .build());
                }
            }
        }
        allocationRepo.saveAll(allocations);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
