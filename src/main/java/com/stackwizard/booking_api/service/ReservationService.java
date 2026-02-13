package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.booking.BookingTranslationService;
import com.stackwizard.booking_api.booking.BookingUom;
import com.stackwizard.booking_api.dto.BookingRequest;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ReservationService {
    private static final int MAX_REQUEST_EXTENSIONS = 3;

    private final ReservationRepository repo;
    private final AllocationRepository allocationRepo;
    private final ResourceRepository resourceRepo;
    private final ProductRepository productRepo;
    private final PriceListEntryRepository priceListRepo;
    private final ResourceCompositionRepository compositionRepo;
    private final BookingTranslationService translationService;
    private final ReservationRequestRepository requestRepo;
    private final TenantConfigService tenantConfigService;

    public ReservationService(ReservationRepository repo,
                              AllocationRepository allocationRepo,
                              ResourceRepository resourceRepo,
                              ProductRepository productRepo,
                              PriceListEntryRepository priceListRepo,
                              ResourceCompositionRepository compositionRepo,
                              BookingTranslationService translationService,
                              ReservationRequestRepository requestRepo,
                              TenantConfigService tenantConfigService) {
        this.repo = repo;
        this.allocationRepo = allocationRepo;
        this.resourceRepo = resourceRepo;
        this.productRepo = productRepo;
        this.priceListRepo = priceListRepo;
        this.compositionRepo = compositionRepo;
        this.translationService = translationService;
        this.requestRepo = requestRepo;
        this.tenantConfigService = tenantConfigService;
    }

    public List<Reservation> findAll() { return repo.findAll(); }
    public Optional<Reservation> findById(Long id) { return repo.findById(id); }
    public List<Reservation> findByRequestId(Long requestId) { return repo.findByRequestId(requestId); }
    public Reservation save(Reservation r) { return repo.save(r); }
    public void deleteById(Long id) { repo.deleteById(id); }

    @Transactional
    public Reservation saveHoldReservation(Reservation r) {
        if (r.getStatus() == null || "CONFIRMED".equalsIgnoreCase(r.getStatus())) {
            r.setStatus("HOLD");
        }
        if (r.getAdults() == null) {
            r.setAdults(0);
        }
        if (r.getChildren() == null) {
            r.setChildren(0);
        }
        if (r.getInfants() == null) {
            r.setInfants(0);
        }
        if (r.getExpiresAt() == null) {
            OffsetDateTime expiresAt = null;
            if (r.getRequest() != null && r.getRequest().getId() != null) {
                ReservationRequest request = resolveRequestForHold(r.getRequest().getId(), r.getTenantId());
                expiresAt = request.getExpiresAt();
                r.setRequest(request);
            }
            if (expiresAt == null) {
                expiresAt = expiresAtForTenant(r.getTenantId());
            }
            r.setExpiresAt(expiresAt);
        }
        return repo.save(r);
    }

    @Transactional
    public List<Allocation> createReservationAndAllocate(Reservation r) {
        if (r.getRequestedResource() == null || r.getRequestedResource().getId() == null) {
            throw new IllegalArgumentException("requestedResource.id is required");
        }

        Resource res = resourceRepo.findById(r.getRequestedResource().getId())
                .orElseThrow(() -> new IllegalArgumentException("Requested resource not found"));

        LocalDateTime starts = r.getStartsAt();
        LocalDateTime ends = r.getEndsAt();
        if (starts == null || ends == null || !ends.isAfter(starts)) {
            throw new IllegalArgumentException("Invalid reservation time range");
        }

        Reservation saved = saveHoldReservation(r);

        List<ResourceComposition> members = compositionRepo.findByParentResourceId(res.getId());
        boolean isComposition = isCompositionResource(res);
        List<Allocation> allocations = new ArrayList<>();
        if (members.isEmpty()) {
            if (isComposition) {
                throw new IllegalStateException("Composition resource has no members");
            }
            ensureAvailability(res, starts, ends);
            allocations.add(Allocation.builder()
                    .tenantId(saved.getTenantId())
                    .reservation(saved)
                    .requestedResource(res)
                    .allocatedResource(res)
                    .resourceKind(res.getKind())
                    .compositResource(false)
                    .compositResourceId(null)
                    .startsAt(saved.getStartsAt())
                    .endsAt(saved.getEndsAt())
                    .status("HOLD")
                    .expiresAt(saved.getExpiresAt())
                    .build());
        } else {
            for (ResourceComposition member : members) {
                Resource memberResource = member.getMemberResource();
                int memberQty = member.getQty() != null ? member.getQty() : 1;
                for (int i = 0; i < memberQty; i++) {
                    ensureAvailability(memberResource, starts, ends);
                    allocations.add(Allocation.builder()
                            .tenantId(saved.getTenantId())
                            .reservation(saved)
                            .requestedResource(res)
                            .allocatedResource(memberResource)
                            .resourceKind(memberResource.getKind())
                            .compositResource(true)
                            .compositResourceId(res.getId())
                            .startsAt(saved.getStartsAt())
                            .endsAt(saved.getEndsAt())
                            .status("HOLD")
                            .expiresAt(saved.getExpiresAt())
                            .build());
                }
            }
        }

        return allocationRepo.saveAll(allocations);
    }

    @Transactional
    public List<Allocation> createBooking(BookingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Booking request is required");
        }
        Long tenantId = TenantResolver.requireTenantId(request.getTenantId());
        if (request.getResourceId() == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }

        Resource requested = resourceRepo.findById(request.getResourceId())
                .orElseThrow(() -> new IllegalArgumentException("Requested resource not found"));
        Product product = resolveProduct(request, requested);

        validateProductResource(product, requested);

        String uom = BookingUom.normalize(request.getUom());
        validateUomAllowed(product, uom);

        List<PriceListEntry> priceEntries = priceListRepo.findForProductUomOnDate(
                product.getId(), uom, request.getCurrency(), tenantId, request.getServiceDate());
        if (priceEntries.isEmpty()) {
            throw new IllegalArgumentException("No price for product and uom on date");
        }

        Integer qty = request.getQty() != null ? request.getQty() : 1;
        if (request.getEndTime() != null || request.getEndDate() != null) {
            if (request.getStartTime() == null) {
                throw new IllegalArgumentException("startTime is required when endTime is provided");
            }
            if (request.getServiceDate() == null) {
                throw new IllegalArgumentException("serviceDate is required when endTime is provided");
            }
            if (!"HOUR".equals(uom)) {
                // Ignore endTime/endDate for DAY bookings
                qty = request.getQty() != null ? request.getQty() : 1;
            } else {
            if (request.getEndTime() == null) {
                throw new IllegalArgumentException("endTime is required when endDate is provided");
            }
            if (request.getEndDate() != null && !request.getEndDate().equals(request.getServiceDate())) {
                throw new IllegalArgumentException("endDate must match serviceDate");
            }
            long minutes = java.time.Duration.between(request.getStartTime(), request.getEndTime()).toMinutes();
            if (minutes <= 0 || minutes % 60 != 0) {
                throw new IllegalArgumentException("endTime must be after startTime and align to whole hours");
            }
            qty = (int) (minutes / 60);
            }
        }
        BookingTranslationService.TranslatedPeriod period;
        if ("DAY".equals(uom) || "HOUR".equals(uom)) {
            period = translationService.translate(
                    uom,
                    qty,
                    tenantId,
                    requested.getLocation() != null ? requested.getLocation().getId() : null,
                    request.getServiceDate(),
                    request.getStartTime());
        } else {
            PriceListEntry entry = priceEntries.get(0);
            if (entry.getStartTime() == null || entry.getEndTime() == null) {
                period = translationService.translate(
                        "DAY",
                        1,
                        tenantId,
                        requested.getLocation() != null ? requested.getLocation().getId() : null,
                        request.getServiceDate(),
                        null);
            } else {
                period = translationService.translateFixedWindow(
                        entry.getStartTime(),
                        entry.getEndTime(),
                        tenantId,
                        requested.getLocation() != null ? requested.getLocation().getId() : null,
                        request.getServiceDate());
            }
        }

        ReservationRequest reservationRequest = resolveOrCreateRequest(request, tenantId);

        Reservation reservation = Reservation.builder()
                .tenantId(tenantId)
                .productId(product.getId())
                .request(reservationRequest)
                .requestType(reservationRequest.getType())
                .requestedResource(requested)
                .startsAt(period.start())
                .endsAt(period.end())
                .status("HOLD")
                .expiresAt(reservationRequest.getExpiresAt())
                .adults(request.getAdults() != null ? request.getAdults() : 0)
                .children(request.getChildren() != null ? request.getChildren() : 0)
                .infants(request.getInfants() != null ? request.getInfants() : 0)
                .customerName(request.getCustomerName())
                .build();

        Reservation saved = repo.save(reservation);

        List<ResourceComposition> members = compositionRepo.findByParentResourceId(requested.getId());
        List<Allocation> allocations = new ArrayList<>();
        boolean isComposition = isCompositionResource(requested);
        if (members.isEmpty()) {
            if (isComposition) {
                throw new IllegalStateException("Composition resource has no members");
            }
            ensureAvailability(requested, period.start(), period.end());
            allocations.add(buildAllocation(saved, requested, requested, product, uom, qty, false, null));
        } else {
            for (ResourceComposition member : members) {
                Resource memberResource = member.getMemberResource();
                int memberQty = member.getQty() != null ? member.getQty() : 1;
                for (int i = 0; i < memberQty; i++) {
                    ensureAvailability(memberResource, period.start(), period.end());
                    allocations.add(buildAllocation(saved, requested, memberResource, product, uom, qty, true, requested.getId()));
                }
            }
        }

        return allocationRepo.saveAll(allocations);
    }

    private ReservationRequest resolveOrCreateRequest(BookingRequest request, Long tenantId) {
        if (request.getRequestId() != null) {
            return resolveRequestForHold(request.getRequestId(), tenantId);
        }
        ReservationRequest.Type type = ReservationRequest.Type.EXTERNAL;
        if (request.getRequestType() != null && !request.getRequestType().isBlank()) {
            type = ReservationRequest.Type.valueOf(request.getRequestType().trim().toUpperCase());
        }
        ReservationRequest newRequest = ReservationRequest.builder()
                .tenantId(tenantId)
                .type(type)
                .status(ReservationRequest.Status.DRAFT)
                .expiresAt(expiresAtForTenant(tenantId))
                .extensionCount(0)
                .build();
        return requestRepo.save(newRequest);
    }

    @Transactional
    public void deleteReservationDraftOnly(Long reservationId) {
        Reservation reservation = repo.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        if (isExpired(reservation.getExpiresAt())) {
            throw new IllegalStateException("Reservation expired");
        }
        if ("CONFIRMED".equalsIgnoreCase(reservation.getStatus())) {
            throw new IllegalStateException("Confirmed reservations cannot be deleted");
        }
        ReservationRequest request = reservation.getRequest();
        if (request == null || request.getStatus() != ReservationRequest.Status.DRAFT) {
            throw new IllegalStateException("Reservation can be deleted only in DRAFT request");
        }
        if (isExpired(request.getExpiresAt())) {
            throw new IllegalStateException("Reservation request expired");
        }
        allocationRepo.deleteByReservationId(reservationId);
        repo.deleteById(reservationId);
    }

    @Transactional
    public void finalizeRequest(Long requestId) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (isExpired(request.getExpiresAt())) {
            throw new IllegalStateException("Reservation request expired");
        }
        if (request.getStatus() == ReservationRequest.Status.FINALIZED) {
            return;
        }
        request.setStatus(ReservationRequest.Status.FINALIZED);
        request.setExpiresAt(null);
        requestRepo.save(request);

        List<Reservation> reservations = repo.findByRequestId(requestId);
        for (Reservation reservation : reservations) {
            if (!"CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                reservation.setStatus("CONFIRMED");
                reservation.setExpiresAt(null);
            }
        }
        repo.saveAll(reservations);

        List<Long> reservationIds = reservations.stream().map(Reservation::getId).toList();
        if (!reservationIds.isEmpty()) {
            List<Allocation> allocations = allocationRepo.findByReservationIdIn(reservationIds);
            for (Allocation allocation : allocations) {
                Reservation reservation = allocation.getReservation();
                if (reservation != null && "CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                    continue;
                }
                allocation.setStatus("CONFIRMED");
                allocation.setExpiresAt(null);
            }
            allocationRepo.saveAll(allocations);
        }
    }

    @Transactional
    public ReservationRequest extendRequestTtl(Long requestId, Integer minutes) {
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (request.getStatus() != ReservationRequest.Status.DRAFT) {
            throw new IllegalStateException("Only DRAFT requests can be extended");
        }
        if (extensionCount(request) >= MAX_REQUEST_EXTENSIONS) {
            throw new IllegalStateException("Reservation request can be extended at most 3 times");
        }

        int extensionMinutes = minutes != null ? minutes : tenantConfigService.holdTtlMinutes(request.getTenantId());
        if (extensionMinutes <= 0) {
            throw new IllegalArgumentException("minutes must be greater than 0");
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime base = request.getExpiresAt() != null && request.getExpiresAt().isAfter(now)
                ? request.getExpiresAt()
                : now;
        OffsetDateTime newExpiresAt = base.plusMinutes(extensionMinutes);

        request.setExpiresAt(newExpiresAt);
        request.setExtensionCount(extensionCount(request) + 1);
        ReservationRequest savedRequest = requestRepo.save(request);

        List<Reservation> reservations = repo.findByRequestId(requestId);
        for (Reservation reservation : reservations) {
            if (!"CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                reservation.setExpiresAt(newExpiresAt);
            }
        }
        repo.saveAll(reservations);

        List<Long> reservationIds = reservations.stream().map(Reservation::getId).toList();
        if (!reservationIds.isEmpty()) {
            List<Allocation> allocations = allocationRepo.findByReservationIdIn(reservationIds);
            for (Allocation allocation : allocations) {
                Reservation reservation = allocation.getReservation();
                if (reservation != null && "CANCELLED".equalsIgnoreCase(reservation.getStatus())) {
                    continue;
                }
                allocation.setExpiresAt(newExpiresAt);
            }
            allocationRepo.saveAll(allocations);
        }

        return savedRequest;
    }

    private Allocation buildAllocation(Reservation reservation,
                                       Resource requested,
                                       Resource allocated,
                                       Product product,
                                       String uom,
                                       int qty,
                                       boolean compositResource,
                                       Long compositResourceId) {
        return Allocation.builder()
                .tenantId(reservation.getTenantId())
                .productId(product.getId())
                .uom(uom)
                .qty(qty)
                .reservation(reservation)
                .requestedResource(requested)
                .allocatedResource(allocated)
                .resourceKind(allocated.getKind())
                .compositResource(compositResource)
                .compositResourceId(compositResourceId)
                .startsAt(reservation.getStartsAt())
                .endsAt(reservation.getEndsAt())
                .status("HOLD")
                .expiresAt(reservation.getExpiresAt())
                .build();
    }

    private void validateUomAllowed(Product product, String uom) {
        String defaultUom = product.getDefaultUom();
        if (defaultUom != null && defaultUom.equalsIgnoreCase(uom)) {
            return;
        }
        if (product.getExtraUoms() != null) {
            for (String extra : product.getExtraUoms()) {
                if (extra != null && extra.equalsIgnoreCase(uom)) {
                    return;
                }
            }
        }
        throw new IllegalArgumentException("UOM not allowed for product");
    }

    private boolean isCompositionResource(Resource resource) {
        return resource.getResourceType() != null
                && resource.getResourceType().getCode() != null
                && "COMPOSITION".equalsIgnoreCase(resource.getResourceType().getCode());
    }

    private void validateProductResource(Product product, Resource resource) {
        if (resource.getProduct() != null && resource.getProduct().getId() != null
                && !resource.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException("Product not applicable to resource");
        }
    }

    private Product resolveProduct(BookingRequest request, Resource resource) {
        Long productId = request.getProductId();
        if (productId != null) {
            Product product = productRepo.findById(productId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found"));
            if (resource.getProduct() != null && resource.getProduct().getId() != null
                    && !resource.getProduct().getId().equals(product.getId())) {
                throw new IllegalArgumentException("Product does not match resource product");
            }
            return product;
        }
        if (resource.getProduct() == null || resource.getProduct().getId() == null) {
            throw new IllegalArgumentException("productId is required");
        }
        return resource.getProduct();
    }

    private void ensureAvailability(Resource resource, LocalDateTime starts, LocalDateTime ends) {
        long overlapping = allocationRepo.countActiveByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(
                resource.getId(), ends, starts);

        int capacity = resource.getUnitCount() != null ? resource.getUnitCount() : 1;
        if ("POOL".equalsIgnoreCase(resource.getKind())) {
            if (resource.getPoolTotalUnits() == null) throw new IllegalStateException("Pool resource missing poolTotalUnits");
            capacity = resource.getPoolTotalUnits();
        }

        if (overlapping >= capacity) {
            throw new IllegalStateException("No availability for the requested resource in the given time range");
        }
    }

    private OffsetDateTime expiresAtForTenant(Long tenantId) {
        int minutes = tenantConfigService.holdTtlMinutes(tenantId);
        return OffsetDateTime.now().plusMinutes(minutes);
    }

    private ReservationRequest resolveRequestForHold(Long requestId, Long tenantId) {
        ReservationRequest existing = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("requestId not found"));
        if (!tenantId.equals(existing.getTenantId())) {
            throw new IllegalArgumentException("requestId does not match tenant");
        }
        if (existing.getStatus() != ReservationRequest.Status.DRAFT) {
            throw new IllegalStateException("Reservation request is not in DRAFT status");
        }
        if (isExpired(existing.getExpiresAt())) {
            if (extensionCount(existing) >= MAX_REQUEST_EXTENSIONS) {
                throw new IllegalStateException("Reservation request can be extended at most 3 times");
            }
            if (repo.existsByRequestId(existing.getId())) {
                throw new IllegalStateException("Reservation request expired");
            }
            existing.setExpiresAt(expiresAtForTenant(tenantId));
            existing.setExtensionCount(extensionCount(existing) + 1);
            return requestRepo.save(existing);
        }
        return existing;
    }

    private int extensionCount(ReservationRequest request) {
        return request.getExtensionCount() != null ? request.getExtensionCount() : 0;
    }

    private boolean isExpired(OffsetDateTime expiresAt) {
        return expiresAt != null && expiresAt.isBefore(OffsetDateTime.now());
    }
}
