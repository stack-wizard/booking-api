package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.booking.BookingTranslationService;
import com.stackwizard.booking_api.booking.BookingUom;
import com.stackwizard.booking_api.dto.BookingRequest;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ReservationService {
    private final ReservationRepository repo;
    private final AllocationRepository allocationRepo;
    private final ResourceRepository resourceRepo;
    private final ProductRepository productRepo;
    private final PriceListEntryRepository priceListRepo;
    private final ResourceCompositionRepository compositionRepo;
    private final BookingTranslationService translationService;

    public ReservationService(ReservationRepository repo,
                              AllocationRepository allocationRepo,
                              ResourceRepository resourceRepo,
                              ProductRepository productRepo,
                              PriceListEntryRepository priceListRepo,
                              ResourceCompositionRepository compositionRepo,
                              BookingTranslationService translationService) {
        this.repo = repo;
        this.allocationRepo = allocationRepo;
        this.resourceRepo = resourceRepo;
        this.productRepo = productRepo;
        this.priceListRepo = priceListRepo;
        this.compositionRepo = compositionRepo;
        this.translationService = translationService;
    }

    public List<Reservation> findAll() { return repo.findAll(); }
    public Optional<Reservation> findById(Long id) { return repo.findById(id); }
    public Reservation save(Reservation r) { return repo.save(r); }
    public void deleteById(Long id) { repo.deleteById(id); }

    @Transactional
    public Allocation createReservationAndAllocate(Reservation r) {
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

        long overlapping = allocationRepo.countByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(res.getId(), ends, starts);

        int capacity = res.getUnitCount() != null ? res.getUnitCount() : 1;
        if ("POOL".equalsIgnoreCase(res.getKind())) {
            if (res.getPoolTotalUnits() == null) throw new IllegalStateException("Pool resource missing poolTotalUnits");
            capacity = res.getPoolTotalUnits();
        }

        if (overlapping >= capacity) {
            throw new IllegalStateException("No availability for the requested resource in the given time range");
        }

        Reservation saved = repo.save(r);

        Allocation a = Allocation.builder()
                .tenantId(saved.getTenantId())
                .reservation(saved)
                .requestedResource(res)
                .allocatedResource(res)
                .resourceKind(res.getKind())
                .compositResource(false)
                .compositResourceId(null)
                .startsAt(saved.getStartsAt())
                .endsAt(saved.getEndsAt())
                .status("CONFIRMED")
                .build();

        return allocationRepo.save(a);
    }

    @Transactional
    public List<Allocation> createBooking(BookingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Booking request is required");
        }
        if (request.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
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

        BookingUom uom = BookingUom.from(request.getUom());
        validateUomAllowed(product, uom);

        boolean hasPrice = priceListRepo.existsByProductIdAndUomAndCurrency(
                product.getId(), uom.name(), request.getCurrency());
        if (!hasPrice) {
            throw new IllegalArgumentException("No price for product and uom");
        }

        Integer qty = request.getQty() != null ? request.getQty() : 1;
        if (request.getEndTime() != null || request.getEndDate() != null) {
            if (request.getStartTime() == null) {
                throw new IllegalArgumentException("startTime is required when endTime is provided");
            }
            if (request.getServiceDate() == null) {
                throw new IllegalArgumentException("serviceDate is required when endTime is provided");
            }
            if (uom != BookingUom.HOUR) {
                throw new IllegalArgumentException("endTime is only supported for HOUR bookings");
            }
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
        BookingTranslationService.TranslatedPeriod period = translationService.translate(
                uom,
                qty,
                request.getTenantId(),
                requested.getLocation() != null ? requested.getLocation().getId() : null,
                request.getServiceDate(),
                request.getStartTime());

        Reservation reservation = Reservation.builder()
                .tenantId(request.getTenantId())
                .productId(product.getId())
                .requestedResource(requested)
                .startsAt(period.start())
                .endsAt(period.end())
                .status("CONFIRMED")
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

    private Allocation buildAllocation(Reservation reservation,
                                       Resource requested,
                                       Resource allocated,
                                       Product product,
                                       BookingUom uom,
                                       int qty,
                                       boolean compositResource,
                                       Long compositResourceId) {
        return Allocation.builder()
                .tenantId(reservation.getTenantId())
                .productId(product.getId())
                .uom(uom.name())
                .qty(qty)
                .reservation(reservation)
                .requestedResource(requested)
                .allocatedResource(allocated)
                .resourceKind(allocated.getKind())
                .compositResource(compositResource)
                .compositResourceId(compositResourceId)
                .startsAt(reservation.getStartsAt())
                .endsAt(reservation.getEndsAt())
                .status("CONFIRMED")
                .build();
    }

    private void validateUomAllowed(Product product, BookingUom uom) {
        String defaultUom = product.getDefaultUom();
        if (defaultUom != null && defaultUom.equalsIgnoreCase(uom.name())) {
            return;
        }
        if (product.getExtraUoms() != null) {
            for (String extra : product.getExtraUoms()) {
                if (extra != null && extra.equalsIgnoreCase(uom.name())) {
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
        long overlapping = allocationRepo.countByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(
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
}
