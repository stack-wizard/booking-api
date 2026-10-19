package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.repository.BookingFunctionItemRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
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
public class BookingPackageService {
    private static final int DEFAULT_FUNCTION_MINUTES = 480;

    private final BookingService bookingService;
    private final BookingFunctionService functionService;
    private final PackageService packageService;
    private final BookingFunctionRepository functionRepo;
    private final BookingFunctionItemRepository itemRepo;
    private final ProductRepository productRepo;
    private final BookingReservationSync reservationSync;

    public BookingPackageService(BookingService bookingService,
                               BookingFunctionService functionService,
                               PackageService packageService,
                               BookingFunctionRepository functionRepo,
                               BookingFunctionItemRepository itemRepo,
                               ProductRepository productRepo,
                               BookingReservationSync reservationSync) {
        this.bookingService = bookingService;
        this.functionService = functionService;
        this.packageService = packageService;
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.productRepo = productRepo;
        this.reservationSync = reservationSync;
    }

    @Transactional
    public List<BookingFunction> apply(Long bookingId, BookingDtos.ApplyPackageRequest request) {
        if (request == null || request.getPackageProductId() == null) {
            throw new IllegalArgumentException("packageProductId is required");
        }
        if (request.getStartsAt() == null) {
            throw new IllegalArgumentException("startsAt is required");
        }
        Booking booking = bookingService.requireContentEditable(bookingId);
        Integer pax = request.getPax() != null ? request.getPax() : BookingItemPricing.billablePax(booking);
        if (pax == null || pax <= 0) {
            throw new IllegalArgumentException("pax is required (set expected pax on the booking or pass pax)");
        }
        Long property = functionService.resolveProperty(booking, BookingFunction.builder().resourceId(request.getResourceId()).build());
        PackageService.PackageQuote quote = packageService.quote(booking.getTenantId(), property,
                request.getPackageProductId(), request.getStartsAt().toLocalDate(), pax, booking.getCurrency());
        if (quote.packagePricing() == Product.PackagePricing.SPLIT_FIXED && quote.difference().signum() != 0) {
            throw new IllegalStateException("Package components differ from the package price by " + quote.difference()
                    + " per person; fix the package before applying it");
        }
        LocalDateTime start = request.getStartsAt();

        List<BookingFunction> created = new ArrayList<>();
        Map<ProductComponent, BookingFunction> generatedBy = new IdentityHashMap<>();
        for (PackageService.ComponentQuote line : quote.components()) {
            ProductComponent component = line.component();
            if (component.getFunctionType() == null) {
                continue;
            }
            LocalDateTime starts = start.plusMinutes(offset(component));
            LocalDateTime ends = starts.plusMinutes(duration(component));
            boolean overlapsPrevious = created.stream().anyMatch(f ->
                    f.getOccupancyStartsAt().isBefore(ends) && f.getOccupancyEndsAt().isAfter(starts));
            BookingFunction function = newFunction(booking, property, quote, component.getFunctionType(),
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
            created.add(newFunction(booking, property, quote, BookingFunction.FunctionType.PLENARY, quote.name(), start,
                    start.plusMinutes(minutes > 0 ? minutes : DEFAULT_FUNCTION_MINUTES), request.getResourceId(), pax, 0));
        }

        List<BookingFunction> saved = new ArrayList<>();
        for (BookingFunction function : created) {
            functionService.normalizeAndValidate(booking, function);
            saved.add(functionRepo.save(function));
        }

        int order = 0;
        for (PackageService.ComponentQuote line : quote.components()) {
            ProductComponent component = line.component();
            LocalDateTime serveAt = component.getStartOffsetMinutes() != null ? start.plusMinutes(component.getStartOffsetMinutes()) : null;
            BookingFunction target = generatedBy.containsKey(component) ? generatedBy.get(component) : pickFunction(saved, serveAt);
            Product product = productRepo.findByIdAndTenantId(component.getComponentProductId(), booking.getTenantId())
                    .orElseThrow(() -> new IllegalArgumentException("Component product not found"));
            boolean perPax = component.getQtyBasis() == ProductComponent.QtyBasis.PER_PAX && component.getQty() == 1;
            BookingFunctionItem item = BookingFunctionItem.builder()
                    .tenantId(property)
                    .bookingFunctionId(target.getId())
                    .productId(product.getId())
                    .packageProductId(quote.packageProductId())
                    .description(component.getIncluded() ? null : product.getName() + " (extra)")
                    .uom(product.getDefaultUom())
                    .qty(line.qty())
                    .qtyBasis(perPax ? BookingFunctionItem.QtyBasis.PER_GUARANTEED_PAX : BookingFunctionItem.QtyBasis.FIXED)
                    .serveAt(serveAt != null && !serveAt.isBefore(target.getStartsAt()) && !serveAt.isAfter(target.getEndsAt()) ? serveAt : null)
                    .unitPrice(line.unitPrice())
                    .discountAmount(java.math.BigDecimal.ZERO)
                    .displayOrder(order++)
                    .build();
            BookingItemPricing.applyAmounts(item);
            itemRepo.save(item);
        }

        for (BookingFunction function : saved) {
            reservationSync.syncFunction(booking, function);
        }
        return saved;
    }

    private BookingFunction newFunction(Booking booking, Long property, PackageService.PackageQuote quote, BookingFunction.FunctionType type,
                                      String name, LocalDateTime starts, LocalDateTime ends, Long resourceId,
                                      Integer pax, int order) {
        return BookingFunction.builder()
                .tenantId(property)
                .bookingId(booking.getId())
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
    static String functionName(BookingFunction.FunctionType type, String packageName, String productName) {
        return switch (type) {
            case PLENARY, BREAKOUT, EXHIBITION -> packageName;
            default -> productName;
        };
    }

    private static BookingFunction pickFunction(List<BookingFunction> functions, LocalDateTime serveAt) {
        if (serveAt != null) {
            for (BookingFunction function : functions) {
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
