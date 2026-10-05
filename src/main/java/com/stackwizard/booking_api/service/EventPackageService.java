package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.EventDtos;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a package as a template: it generates ordinary functions and items that are edited freely
 * afterwards. Generated rows keep package_product_id only for reporting.
 */
@Service
public class EventPackageService {
    private static final int DEFAULT_FUNCTION_MINUTES = 480;

    private final EventService eventService;
    private final EventFunctionService functionService;
    private final PackageService packageService;
    private final EventFunctionRepository functionRepo;
    private final EventFunctionItemRepository itemRepo;
    private final ProductRepository productRepo;
    private final EventReservationSync reservationSync;

    public EventPackageService(EventService eventService,
                               EventFunctionService functionService,
                               PackageService packageService,
                               EventFunctionRepository functionRepo,
                               EventFunctionItemRepository itemRepo,
                               ProductRepository productRepo,
                               EventReservationSync reservationSync) {
        this.eventService = eventService;
        this.functionService = functionService;
        this.packageService = packageService;
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.productRepo = productRepo;
        this.reservationSync = reservationSync;
    }

    @Transactional
    public List<EventFunction> apply(Long eventId, EventDtos.ApplyPackageRequest request) {
        if (request == null || request.getPackageProductId() == null) {
            throw new IllegalArgumentException("packageProductId is required");
        }
        if (request.getStartsAt() == null) {
            throw new IllegalArgumentException("startsAt is required");
        }
        Event event = eventService.requireEditable(eventId);
        if (event.getStatus() == Event.Status.ACTUAL) {
            throw new IllegalStateException("Packages cannot be applied to an ACTUAL event");
        }
        Integer pax = request.getPax() != null ? request.getPax() : EventItemPricing.billablePax(event);
        if (pax == null || pax <= 0) {
            throw new IllegalArgumentException("pax is required (set expected pax on the event or pass pax)");
        }
        PackageService.PackageQuote quote = packageService.quote(event.getTenantId(), request.getPackageProductId(),
                request.getStartsAt().toLocalDate(), pax, event.getCurrency());
        if (quote.packagePricing() == Product.PackagePricing.SPLIT_FIXED && quote.difference().signum() != 0) {
            throw new IllegalStateException("Package components differ from the package price by " + quote.difference()
                    + " per person; fix the package before applying it");
        }
        LocalDateTime start = request.getStartsAt();

        List<EventFunction> created = new ArrayList<>();
        Map<ProductComponent, EventFunction> generatedBy = new IdentityHashMap<>();
        for (PackageService.ComponentQuote line : quote.components()) {
            ProductComponent component = line.component();
            if (component.getFunctionType() == null) {
                continue;
            }
            LocalDateTime starts = start.plusMinutes(offset(component));
            LocalDateTime ends = starts.plusMinutes(duration(component));
            boolean overlapsPrevious = created.stream().anyMatch(f ->
                    f.getOccupancyStartsAt().isBefore(ends) && f.getOccupancyEndsAt().isAfter(starts));
            EventFunction function = newFunction(event, quote, component.getFunctionType(),
                    functionName(component.getFunctionType(), quote.name(), line.productName()), starts, ends,
                    overlapsPrevious ? null : request.getResourceId(), pax, created.size());
            created.add(function);
            generatedBy.put(component, function);
        }
        if (created.isEmpty()) {
            int minutes = quote.components().stream()
                    .map(PackageService.ComponentQuote::component)
                    .mapToInt(c -> offset(c) + (c.getDurationMinutes() != null ? c.getDurationMinutes() : 0))
                    .max().orElse(0);
            created.add(newFunction(event, quote, EventFunction.FunctionType.PLENARY, quote.name(), start,
                    start.plusMinutes(minutes > 0 ? minutes : DEFAULT_FUNCTION_MINUTES), request.getResourceId(), pax, 0));
        }

        List<EventFunction> saved = new ArrayList<>();
        for (EventFunction function : created) {
            functionService.normalizeAndValidate(event, function);
            saved.add(functionRepo.save(function));
        }

        int order = 0;
        for (PackageService.ComponentQuote line : quote.components()) {
            ProductComponent component = line.component();
            LocalDateTime serveAt = component.getStartOffsetMinutes() != null ? start.plusMinutes(component.getStartOffsetMinutes()) : null;
            EventFunction target = generatedBy.containsKey(component) ? generatedBy.get(component) : pickFunction(saved, serveAt);
            Product product = productRepo.findByIdAndTenantId(component.getComponentProductId(), event.getTenantId())
                    .orElseThrow(() -> new IllegalArgumentException("Component product not found"));
            boolean perPax = component.getQtyBasis() == ProductComponent.QtyBasis.PER_PAX && component.getQty() == 1;
            EventFunctionItem item = EventFunctionItem.builder()
                    .tenantId(event.getTenantId())
                    .eventFunctionId(target.getId())
                    .productId(product.getId())
                    .packageProductId(quote.packageProductId())
                    .description(component.getIncluded() ? null : product.getName() + " (extra)")
                    .uom(product.getDefaultUom())
                    .qty(line.qty())
                    .qtyBasis(perPax ? EventFunctionItem.QtyBasis.PER_GUARANTEED_PAX : EventFunctionItem.QtyBasis.FIXED)
                    .serveAt(serveAt != null && !serveAt.isBefore(target.getStartsAt()) && !serveAt.isAfter(target.getEndsAt()) ? serveAt : null)
                    .unitPrice(line.unitPrice())
                    .discountAmount(java.math.BigDecimal.ZERO)
                    .displayOrder(order++)
                    .build();
            EventItemPricing.applyAmounts(item);
            itemRepo.save(item);
        }

        for (EventFunction function : saved) {
            reservationSync.syncFunction(event, function);
        }
        return saved;
    }

    private EventFunction newFunction(Event event, PackageService.PackageQuote quote, EventFunction.FunctionType type,
                                      String name, LocalDateTime starts, LocalDateTime ends, Long resourceId,
                                      Integer pax, int order) {
        return EventFunction.builder()
                .tenantId(event.getTenantId())
                .eventId(event.getId())
                .resourceId(resourceId)
                .functionType(type)
                .name(name)
                .startsAt(starts)
                .endsAt(ends)
                .occupancyStartsAt(starts)
                .occupancyEndsAt(ends)
                .pax(pax)
                .packageProductId(quote.packageProductId())
                .displayOrder(order)
                .build();
    }

    /** Session-type functions carry the package name; meals and breaks keep the component's name. */
    static String functionName(EventFunction.FunctionType type, String packageName, String productName) {
        return switch (type) {
            case PLENARY, BREAKOUT, EXHIBITION -> packageName;
            default -> productName;
        };
    }

    private static EventFunction pickFunction(List<EventFunction> functions, LocalDateTime serveAt) {
        if (serveAt != null) {
            for (EventFunction function : functions) {
                if (!serveAt.isBefore(function.getStartsAt()) && !serveAt.isAfter(function.getEndsAt())) {
                    return function;
                }
            }
        }
        return functions.getFirst();
    }

    private static int offset(ProductComponent component) {
        return component.getStartOffsetMinutes() != null ? component.getStartOffsetMinutes() : 0;
    }

    private static int duration(ProductComponent component) {
        return component.getDurationMinutes() != null ? component.getDurationMinutes() : DEFAULT_FUNCTION_MINUTES;
    }
}
