package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.booking.BookingUom;
import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.BookingFunctionItemRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.TenantContext;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class BookingFunctionService {
    private final BookingService bookingService;
    private final BookingFunctionRepository functionRepo;
    private final BookingFunctionItemRepository itemRepo;
    private final ProductRepository productRepo;
    private final BookingReservationSync reservationSync;
    private final ResourceSetupCapacityService capacityService;
    private final BookingItemPricing itemPricing;
    private final ResourceRepository resourceRepo;
    private final TenantHierarchy hierarchy;
    private final ProductVisibilityService productVisibility;
    private final CrmLookupService lookupService;

    public BookingFunctionService(BookingService bookingService,
                                BookingFunctionRepository functionRepo,
                                BookingFunctionItemRepository itemRepo,
                                ProductRepository productRepo,
                                BookingReservationSync reservationSync,
                                ResourceSetupCapacityService capacityService,
                                BookingItemPricing itemPricing,
                                ResourceRepository resourceRepo,
                                TenantHierarchy hierarchy,
                                ProductVisibilityService productVisibility,
                                CrmLookupService lookupService) {
        this.bookingService = bookingService;
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.productRepo = productRepo;
        this.reservationSync = reservationSync;
        this.capacityService = capacityService;
        this.itemPricing = itemPricing;
        this.resourceRepo = resourceRepo;
        this.hierarchy = hierarchy;
        this.productVisibility = productVisibility;
        this.lookupService = lookupService;
    }

    public List<BookingFunction> functions(Long bookingId) {
        Booking booking = bookingService.requireBooking(bookingId);
        return functionRepo.findForBooking(booking.getTenantId(), booking.getId());
    }

    @Transactional
    public BookingFunction createFunction(Long bookingId, BookingFunction function) {
        Booking booking = requireOpenForPlanning(bookingId);
        function.setId(null);
        function.setOperaEventId(null);
        function.setTenantId(resolveProperty(booking, function));
        function.setBookingId(booking.getId());
        if (function.getDisplayOrder() == null) {
            function.setDisplayOrder(0);
        }
        function.setSetupStyle(lookupService.requireCode(booking.getTenantId(), CrmLookup.Kind.SETUP_STYLE,
                function.getSetupStyle(), null));
        function.setEventType(lookupService.requireCode(booking.getTenantId(), CrmLookup.Kind.EVENT_TYPE,
                function.getEventType(), null));
        normalizeAndValidate(booking, function);
        BookingFunction saved = functionRepo.save(function);
        reservationSync.syncFunction(booking, saved);
        reservationSync.pushOperaBlock(booking);
        return saved;
    }

    @Transactional
    public BookingFunction updateFunction(Long functionId, BookingFunction changes) {
        BookingFunction existing = requireFunction(functionId);
        Booking booking = requireOpenForPlanning(existing.getBookingId());
        changes.setTenantId(changes.getResourceId() == null ? existing.getTenantId() : null);
        Long property = resolveProperty(booking, changes);
        if (!property.equals(existing.getTenantId())) {
            moveToProperty(booking, existing, property);
        }
        existing.setResourceId(changes.getResourceId());
        existing.setFunctionType(changes.getFunctionType());
        existing.setName(changes.getName());
        existing.setSetupStyle(lookupService.requireCode(booking.getTenantId(), CrmLookup.Kind.SETUP_STYLE,
                changes.getSetupStyle(), existing.getSetupStyle()));
        existing.setEventType(lookupService.requireCode(booking.getTenantId(), CrmLookup.Kind.EVENT_TYPE,
                changes.getEventType(), existing.getEventType()));
        existing.setStartsAt(changes.getStartsAt());
        existing.setEndsAt(changes.getEndsAt());
        existing.setOccupancyStartsAt(changes.getOccupancyStartsAt());
        existing.setOccupancyEndsAt(changes.getOccupancyEndsAt());
        existing.setPax(changes.getPax());
        existing.setNotes(changes.getNotes());
        if (changes.getDisplayOrder() != null) {
            existing.setDisplayOrder(changes.getDisplayOrder());
        }
        normalizeAndValidate(booking, existing);
        BookingFunction saved = functionRepo.save(existing);
        validateItemsInsideWindow(saved);
        reservationSync.syncFunction(booking, saved);
        reservationSync.pushOperaBlock(booking);
        return saved;
    }

    @Transactional
    public void deleteFunction(Long functionId) {
        BookingFunction existing = requireFunction(functionId);
        Booking booking = requireOpenForPlanning(existing.getBookingId());
        if (booking.getStatus() == Booking.Status.DEFINITE) {
            throw new IllegalStateException("Functions of a DEFINITE booking cannot be deleted; change the space or time instead");
        }
        reservationSync.removeOperaEvent(existing);
        reservationSync.detachFunction(booking, existing);
        functionRepo.delete(existing);
    }

    /**
     * The hotel that hosts a function: the hotel of its space, otherwise the explicitly sent hotel, the selected
     * hotel of the request or the only hotel of the chain.
     */
    Long resolveProperty(Booking booking, BookingFunction function) {
        Long resolved = resolveUnrestricted(booking, function);
        Long limitedTo = booking.getPropertyTenantId();
        if (limitedTo != null && !limitedTo.equals(resolved)) {
            throw new IllegalArgumentException("The booking is limited to its hotel; this function is in another hotel");
        }
        return resolved;
    }

    private Long resolveUnrestricted(Booking booking, BookingFunction function) {
        Long org = booking.getTenantId();
        if (function.getResourceId() == null && function.getTenantId() == null && booking.getPropertyTenantId() != null) {
            return booking.getPropertyTenantId();
        }
        if (function.getResourceId() != null) {
            Resource space = resourceRepo.findById(function.getResourceId())
                    .filter(r -> hierarchy.isPropertyOf(r.getTenantId(), org))
                    .orElseThrow(() -> new IllegalArgumentException("Space not found: " + function.getResourceId()));
            if (function.getTenantId() != null && !function.getTenantId().equals(space.getTenantId())) {
                throw new IllegalArgumentException("Space does not belong to the selected hotel");
            }
            return space.getTenantId();
        }
        if (function.getTenantId() != null) {
            return hierarchy.requirePropertyOf(function.getTenantId(), org);
        }
        Long selected = TenantContext.getPropertyTenantId();
        if (selected != null) {
            return hierarchy.requirePropertyOf(selected, org);
        }
        List<Long> hotels = hierarchy.propertyIdsOf(org);
        if (hotels.size() == 1) {
            return hotels.get(0);
        }
        throw new IllegalArgumentException("Choose the hotel of the function (propertyId / X-Property-Id)");
    }

    /** A function changed hotel: its generated reservation lines and items follow, the old hold is released. */
    private void moveToProperty(Booking booking, BookingFunction function, Long property) {
        reservationSync.removeOperaEvent(function);
        reservationSync.detachFunction(booking, function);
        List<BookingFunctionItem> items = itemRepo.findByTenantIdAndBookingFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(
                function.getTenantId(), function.getId());
        items.forEach(i -> i.setTenantId(property));
        itemRepo.saveAll(items);
        function.setTenantId(property);
    }

    public List<BookingFunctionItem> items(Long functionId) {
        BookingFunction function = requireFunction(functionId);
        return itemRepo.findByTenantIdAndBookingFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(
                function.getTenantId(), function.getId());
    }

    public List<BookingFunctionItem> itemsForBooking(Long bookingId) {
        Booking booking = bookingService.requireBooking(bookingId);
        List<Long> functionIds = functionRepo.findForBooking(
                booking.getTenantId(), booking.getId()).stream().map(BookingFunction::getId).toList();
        if (functionIds.isEmpty()) {
            return List.of();
        }
        return itemRepo.findForFunctions(booking.getTenantId(), functionIds);
    }

    @Transactional
    public BookingFunctionItem createItem(Long functionId, BookingFunctionItem item) {
        BookingFunction function = requireFunction(functionId);
        Booking booking = bookingService.requireContentEditable(function.getBookingId());
        item.setId(null);
        item.setTenantId(function.getTenantId());
        item.setBookingFunctionId(function.getId());
        if (item.getDisplayOrder() == null) {
            item.setDisplayOrder(0);
        }
        normalizeAndPriceItem(booking, function, item, true);
        return itemRepo.save(item);
    }

    @Transactional
    public BookingFunctionItem updateItem(Long itemId, BookingFunctionItem changes) {
        BookingFunctionItem existing = itemRepo.findForOrgById(TenantResolver.requireOrgTenantId(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));
        BookingFunction function = requireFunction(existing.getBookingFunctionId());
        Booking booking = bookingService.requireContentEditable(function.getBookingId());
        boolean productChanged = changes.getProductId() != null && !changes.getProductId().equals(existing.getProductId());
        if (changes.getProductId() != null) {
            existing.setProductId(changes.getProductId());
        }
        existing.setDescription(changes.getDescription());
        existing.setUom(changes.getUom() != null ? changes.getUom() : productChanged ? null : existing.getUom());
        existing.setQty(changes.getQty());
        existing.setQtyBasis(changes.getQtyBasis() != null ? changes.getQtyBasis() : existing.getQtyBasis());
        existing.setServeAt(changes.getServeAt());
        existing.setUnitPrice(changes.getUnitPrice() != null ? changes.getUnitPrice() : productChanged ? null : existing.getUnitPrice());
        existing.setDiscountAmount(changes.getDiscountAmount());
        existing.setCostAmount(changes.getCostAmount());
        existing.setDietaryNotes(changes.getDietaryNotes());
        existing.setAllergens(changes.getAllergens());
        if (changes.getDisplayOrder() != null) {
            existing.setDisplayOrder(changes.getDisplayOrder());
        }
        normalizeAndPriceItem(booking, function, existing, false);
        return itemRepo.save(existing);
    }

    @Transactional
    public void deleteItem(Long itemId) {
        BookingFunctionItem existing = itemRepo.findForOrgById(TenantResolver.requireOrgTenantId(), itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));
        BookingFunction function = requireFunction(existing.getBookingFunctionId());
        bookingService.requireContentEditable(function.getBookingId());
        itemRepo.delete(existing);
    }

    BookingFunction requireFunction(Long functionId) {
        BookingFunction function = functionRepo.findForOrgById(TenantResolver.requireOrgTenantId(), functionId)
                .orElseThrow(() -> new IllegalArgumentException("Function not found: " + functionId));
        bookingService.requireBooking(function.getBookingId());
        return function;
    }

    private Booking requireOpenForPlanning(Long bookingId) {
        return bookingService.requireContentEditable(bookingId);
    }

    void normalizeAndValidate(Booking booking, BookingFunction function) {
        if (function.getFunctionType() == null) {
            throw new IllegalArgumentException("functionType is required");
        }
        if (function.getStartsAt() == null || function.getEndsAt() == null || !function.getEndsAt().isAfter(function.getStartsAt())) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        if (function.getOccupancyStartsAt() == null) {
            function.setOccupancyStartsAt(function.getStartsAt());
        }
        if (function.getOccupancyEndsAt() == null) {
            function.setOccupancyEndsAt(function.getEndsAt());
        }
        if (function.getOccupancyStartsAt().isAfter(function.getStartsAt())
                || function.getOccupancyEndsAt().isBefore(function.getEndsAt())) {
            throw new IllegalArgumentException("Occupancy window must cover the booking window (setup before, teardown after)");
        }
        LocalDate day = function.getStartsAt().toLocalDate();
        if (day.isBefore(booking.getDateFrom()) || day.isAfter(booking.getDateTo())) {
            throw new IllegalArgumentException("Function day " + day + " is outside the booking dates "
                    + booking.getDateFrom() + " - " + booking.getDateTo());
        }
        if (function.getPax() != null && function.getPax() < 0) {
            throw new IllegalArgumentException("pax must be >= 0");
        }
        if (function.getResourceId() != null) {
            Long property = function.getTenantId() != null ? function.getTenantId() : booking.getTenantId();
            capacityService.requireSpaceResource(property, function.getResourceId());
            if (function.getSetupStyle() != null) {
                Integer capacity = capacityService.capacityFor(property, function.getResourceId(), function.getSetupStyle())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Space has no " + function.getSetupStyle() + " setup"));
                if (function.getPax() != null && function.getPax() > capacity) {
                    throw new IllegalArgumentException("Space seats " + capacity + " in " + function.getSetupStyle()
                            + ", function needs " + function.getPax());
                }
            }
        }
    }

    private void validateItemsInsideWindow(BookingFunction function) {
        for (BookingFunctionItem item : itemRepo.findByTenantIdAndBookingFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(
                function.getTenantId(), function.getId())) {
            requireServeAtInside(function, item.getServeAt());
        }
    }

    void normalizeAndPriceItem(Booking booking, BookingFunction function, BookingFunctionItem item, boolean creating) {
        if (item.getProductId() == null) {
            throw new IllegalArgumentException("productId is required");
        }
        Product product = productRepo.findByIdAndTenantId(item.getProductId(), booking.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + item.getProductId()));
        productVisibility.requireVisible(product, function.getTenantId());
        if (product.getPackagePricing() != null) {
            throw new IllegalArgumentException("Packages are applied to the booking, not added as a single item");
        }
        String uom = BookingUom.normalize(StringUtils.hasText(item.getUom()) ? item.getUom() : product.getDefaultUom());
        if (!uom.equalsIgnoreCase(product.getDefaultUom())
                && (product.getExtraUoms() == null || product.getExtraUoms().stream().noneMatch(uom::equalsIgnoreCase))) {
            throw new IllegalArgumentException("UOM " + uom + " not allowed for " + product.getName());
        }
        item.setUom(uom);
        if (item.getQtyBasis() == null) {
            item.setQtyBasis(BookingFunctionItem.QtyBasis.FIXED);
        }
        if (item.getQtyBasis() == BookingFunctionItem.QtyBasis.PER_GUARANTEED_PAX) {
            Integer pax = BookingItemPricing.billablePax(booking);
            if (pax == null || pax <= 0) {
                throw new IllegalArgumentException("Set expected or guaranteed pax before adding per-pax items");
            }
            item.setQty(pax);
        } else if (item.getQty() == null || item.getQty() <= 0) {
            throw new IllegalArgumentException("qty must be > 0");
        }
        requireServeAtInside(function, item.getServeAt());
        if (item.getUnitPrice() == null) {
            BigDecimal price = itemPricing.catalogPrice(product, uom, booking.getCurrency(), function.getTenantId(),
                            function.getStartsAt().toLocalDate())
                    .orElseThrow(() -> new IllegalArgumentException("No price for " + product.getName() + " (" + uom
                            + "); enter unitPrice"));
            item.setUnitPrice(price);
        } else if (item.getUnitPrice().signum() < 0) {
            throw new IllegalArgumentException("unitPrice must be >= 0");
        }
        if (item.getCostAmount() != null && item.getCostAmount().signum() < 0) {
            throw new IllegalArgumentException("costAmount must be >= 0");
        }
        if (creating && item.getDiscountAmount() == null) {
            item.setDiscountAmount(BigDecimal.ZERO);
        }
        BookingItemPricing.applyAmounts(item);
    }

    private static void requireServeAtInside(BookingFunction function, LocalDateTime serveAt) {
        if (serveAt == null) {
            return;
        }
        if (serveAt.isBefore(function.getStartsAt()) || serveAt.isAfter(function.getEndsAt())) {
            throw new IllegalArgumentException("serveAt " + serveAt.toLocalTime() + " is outside the function "
                    + function.getStartsAt().toLocalTime() + " - " + function.getEndsAt().toLocalTime());
        }
    }
}
