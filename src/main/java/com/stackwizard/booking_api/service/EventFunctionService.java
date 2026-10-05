package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.booking.BookingUom;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class EventFunctionService {
    private final EventService eventService;
    private final EventFunctionRepository functionRepo;
    private final EventFunctionItemRepository itemRepo;
    private final ProductRepository productRepo;
    private final EventReservationSync reservationSync;
    private final ResourceSetupCapacityService capacityService;
    private final EventItemPricing itemPricing;

    public EventFunctionService(EventService eventService,
                                EventFunctionRepository functionRepo,
                                EventFunctionItemRepository itemRepo,
                                ProductRepository productRepo,
                                EventReservationSync reservationSync,
                                ResourceSetupCapacityService capacityService,
                                EventItemPricing itemPricing) {
        this.eventService = eventService;
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.productRepo = productRepo;
        this.reservationSync = reservationSync;
        this.capacityService = capacityService;
        this.itemPricing = itemPricing;
    }

    public List<EventFunction> functions(Long eventId) {
        Event event = eventService.requireEvent(eventId);
        return functionRepo.findByTenantIdAndEventIdOrderByStartsAtAscDisplayOrderAscIdAsc(event.getTenantId(), event.getId());
    }

    @Transactional
    public EventFunction createFunction(Long eventId, EventFunction function) {
        Event event = requireOpenForPlanning(eventId);
        function.setId(null);
        function.setTenantId(event.getTenantId());
        function.setEventId(event.getId());
        if (function.getDisplayOrder() == null) {
            function.setDisplayOrder(0);
        }
        normalizeAndValidate(event, function);
        EventFunction saved = functionRepo.save(function);
        reservationSync.syncFunction(event, saved);
        return saved;
    }

    @Transactional
    public EventFunction updateFunction(Long functionId, EventFunction changes) {
        EventFunction existing = requireFunction(functionId);
        Event event = requireOpenForPlanning(existing.getEventId());
        existing.setResourceId(changes.getResourceId());
        existing.setFunctionType(changes.getFunctionType());
        existing.setName(changes.getName());
        existing.setSetupStyle(changes.getSetupStyle());
        existing.setStartsAt(changes.getStartsAt());
        existing.setEndsAt(changes.getEndsAt());
        existing.setOccupancyStartsAt(changes.getOccupancyStartsAt());
        existing.setOccupancyEndsAt(changes.getOccupancyEndsAt());
        existing.setPax(changes.getPax());
        existing.setNotes(changes.getNotes());
        if (changes.getDisplayOrder() != null) {
            existing.setDisplayOrder(changes.getDisplayOrder());
        }
        normalizeAndValidate(event, existing);
        EventFunction saved = functionRepo.save(existing);
        validateItemsInsideWindow(saved);
        reservationSync.syncFunction(event, saved);
        return saved;
    }

    @Transactional
    public void deleteFunction(Long functionId) {
        EventFunction existing = requireFunction(functionId);
        Event event = requireOpenForPlanning(existing.getEventId());
        if (event.getStatus() == Event.Status.DEFINITE) {
            throw new IllegalStateException("Functions of a DEFINITE event cannot be deleted; change the space or time instead");
        }
        reservationSync.detachFunction(event, existing);
        functionRepo.delete(existing);
    }

    public List<EventFunctionItem> items(Long functionId) {
        EventFunction function = requireFunction(functionId);
        return itemRepo.findByTenantIdAndEventFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(
                function.getTenantId(), function.getId());
    }

    public List<EventFunctionItem> itemsForEvent(Long eventId) {
        Event event = eventService.requireEvent(eventId);
        List<Long> functionIds = functionRepo.findByTenantIdAndEventIdOrderByStartsAtAscDisplayOrderAscIdAsc(
                event.getTenantId(), event.getId()).stream().map(EventFunction::getId).toList();
        if (functionIds.isEmpty()) {
            return List.of();
        }
        return itemRepo.findByTenantIdAndEventFunctionIdIn(event.getTenantId(), functionIds);
    }

    @Transactional
    public EventFunctionItem createItem(Long functionId, EventFunctionItem item) {
        EventFunction function = requireFunction(functionId);
        Event event = eventService.requireEditable(function.getEventId());
        item.setId(null);
        item.setTenantId(function.getTenantId());
        item.setEventFunctionId(function.getId());
        if (item.getDisplayOrder() == null) {
            item.setDisplayOrder(0);
        }
        normalizeAndPriceItem(event, function, item, true);
        return itemRepo.save(item);
    }

    @Transactional
    public EventFunctionItem updateItem(Long itemId, EventFunctionItem changes) {
        EventFunctionItem existing = itemRepo.findByIdAndTenantId(itemId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));
        EventFunction function = requireFunction(existing.getEventFunctionId());
        Event event = eventService.requireEditable(function.getEventId());
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
        normalizeAndPriceItem(event, function, existing, false);
        return itemRepo.save(existing);
    }

    @Transactional
    public void deleteItem(Long itemId) {
        EventFunctionItem existing = itemRepo.findByIdAndTenantId(itemId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));
        EventFunction function = requireFunction(existing.getEventFunctionId());
        eventService.requireEditable(function.getEventId());
        itemRepo.delete(existing);
    }

    EventFunction requireFunction(Long functionId) {
        EventFunction function = functionRepo.findByIdAndTenantId(functionId,
                        TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Function not found: " + functionId));
        eventService.requireEvent(function.getEventId());
        return function;
    }

    private Event requireOpenForPlanning(Long eventId) {
        Event event = eventService.requireEditable(eventId);
        if (event.getStatus() == Event.Status.ACTUAL) {
            throw new IllegalStateException("Functions of an ACTUAL event cannot be changed");
        }
        return event;
    }

    void normalizeAndValidate(Event event, EventFunction function) {
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
            throw new IllegalArgumentException("Occupancy window must cover the event window (setup before, teardown after)");
        }
        LocalDate day = function.getStartsAt().toLocalDate();
        if (day.isBefore(event.getDateFrom()) || day.isAfter(event.getDateTo())) {
            throw new IllegalArgumentException("Function day " + day + " is outside the event dates "
                    + event.getDateFrom() + " - " + event.getDateTo());
        }
        if (function.getPax() != null && function.getPax() < 0) {
            throw new IllegalArgumentException("pax must be >= 0");
        }
        if (function.getResourceId() != null) {
            capacityService.requireSpaceResource(event.getTenantId(), function.getResourceId());
            if (function.getSetupStyle() != null) {
                Integer capacity = capacityService.capacityFor(event.getTenantId(), function.getResourceId(), function.getSetupStyle())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Space has no " + function.getSetupStyle() + " setup"));
                if (function.getPax() != null && function.getPax() > capacity) {
                    throw new IllegalArgumentException("Space seats " + capacity + " in " + function.getSetupStyle()
                            + ", function needs " + function.getPax());
                }
            }
        }
    }

    private void validateItemsInsideWindow(EventFunction function) {
        for (EventFunctionItem item : itemRepo.findByTenantIdAndEventFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(
                function.getTenantId(), function.getId())) {
            requireServeAtInside(function, item.getServeAt());
        }
    }

    void normalizeAndPriceItem(Event event, EventFunction function, EventFunctionItem item, boolean creating) {
        if (item.getProductId() == null) {
            throw new IllegalArgumentException("productId is required");
        }
        Product product = productRepo.findByIdAndTenantId(item.getProductId(), event.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + item.getProductId()));
        if (product.getPackagePricing() != null) {
            throw new IllegalArgumentException("Packages are applied to the event, not added as a single item");
        }
        String uom = BookingUom.normalize(StringUtils.hasText(item.getUom()) ? item.getUom() : product.getDefaultUom());
        if (!uom.equalsIgnoreCase(product.getDefaultUom())
                && (product.getExtraUoms() == null || product.getExtraUoms().stream().noneMatch(uom::equalsIgnoreCase))) {
            throw new IllegalArgumentException("UOM " + uom + " not allowed for " + product.getName());
        }
        item.setUom(uom);
        if (item.getQtyBasis() == null) {
            item.setQtyBasis(EventFunctionItem.QtyBasis.FIXED);
        }
        if (item.getQtyBasis() == EventFunctionItem.QtyBasis.PER_GUARANTEED_PAX) {
            Integer pax = EventItemPricing.billablePax(event);
            if (pax == null || pax <= 0) {
                throw new IllegalArgumentException("Set expected or guaranteed pax before adding per-pax items");
            }
            item.setQty(pax);
        } else if (item.getQty() == null || item.getQty() <= 0) {
            throw new IllegalArgumentException("qty must be > 0");
        }
        requireServeAtInside(function, item.getServeAt());
        if (item.getUnitPrice() == null) {
            BigDecimal price = itemPricing.catalogPrice(product, uom, event.getCurrency(), event.getTenantId(),
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
        EventItemPricing.applyAmounts(item);
    }

    private static void requireServeAtInside(EventFunction function, LocalDateTime serveAt) {
        if (serveAt == null) {
            return;
        }
        if (serveAt.isBefore(function.getStartsAt()) || serveAt.isAfter(function.getEndsAt())) {
            throw new IllegalArgumentException("serveAt " + serveAt.toLocalTime() + " is outside the function "
                    + function.getStartsAt().toLocalTime() + " - " + function.getEndsAt().toLocalTime());
        }
    }
}
