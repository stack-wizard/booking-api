package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.model.ProductImage;
import com.stackwizard.booking_api.model.ProductPackageListing;
import com.stackwizard.booking_api.repository.ProductComponentRepository;
import com.stackwizard.booking_api.repository.ProductPackageListingRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CMS side of packages: listing fields, the published catalogue and space availability for package + date + pax.
 */
@Service
public class PackageCatalogService {
    static final int FULL_DAY_MINUTES = 480;
    static final int HALF_DAY_MINUTES = 240;

    private final ProductRepository productRepo;
    private final ProductComponentRepository componentRepo;
    private final ProductPackageListingRepository listingRepo;
    private final PackageService packageService;
    private final ResourceSetupCapacityService spaceService;

    public PackageCatalogService(ProductRepository productRepo,
                                 ProductComponentRepository componentRepo,
                                 ProductPackageListingRepository listingRepo,
                                 PackageService packageService,
                                 ResourceSetupCapacityService spaceService) {
        this.productRepo = productRepo;
        this.componentRepo = componentRepo;
        this.listingRepo = listingRepo;
        this.packageService = packageService;
        this.spaceService = spaceService;
    }

    @Transactional(readOnly = true)
    public ProductPackageListing listing(Long productId) {
        Long tenantId = TenantResolver.requireTenantId();
        Product product = requirePackage(tenantId, productId);
        return listingRepo.findByTenantIdAndProductId(tenantId, product.getId())
                .orElseGet(() -> defaults(tenantId, product.getId()));
    }

    @Transactional
    public ProductPackageListing saveListing(Long productId, ProductPackageListing incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Long tenantId = TenantResolver.requireTenantId();
        Product product = requirePackage(tenantId, productId);
        ProductPackageListing listing = listingRepo.findByTenantIdAndProductId(tenantId, product.getId())
                .orElseGet(() -> defaults(tenantId, product.getId()));
        if (incoming.getValidFrom() != null && incoming.getValidTo() != null
                && incoming.getValidTo().isBefore(incoming.getValidFrom())) {
            throw new IllegalArgumentException("validTo must not be before validFrom");
        }
        if (incoming.getMinPax() != null && incoming.getMinPax() <= 0
                || incoming.getMaxPax() != null && incoming.getMaxPax() <= 0) {
            throw new IllegalArgumentException("minPax and maxPax must be > 0");
        }
        if (incoming.getMinPax() != null && incoming.getMaxPax() != null && incoming.getMaxPax() < incoming.getMinPax()) {
            throw new IllegalArgumentException("maxPax must be >= minPax");
        }
        listing.setPublicName(blankToNull(incoming.getPublicName()));
        listing.setPublicDescription(blankToNull(incoming.getPublicDescription()));
        listing.setValidFrom(incoming.getValidFrom());
        listing.setValidTo(incoming.getValidTo());
        listing.setMinPax(incoming.getMinPax());
        listing.setMaxPax(incoming.getMaxPax());
        listing.setDuration(incoming.getDuration() != null ? incoming.getDuration() : ProductPackageListing.Duration.FULL_DAY);
        listing.setDefaultStartTime(incoming.getDefaultStartTime() != null ? incoming.getDefaultStartTime() : LocalTime.of(9, 0));
        listing.setSetupStyle(incoming.getSetupStyle());
        listing.setPublished(Boolean.TRUE.equals(incoming.getPublished()));
        listing.setUpdatedAt(OffsetDateTime.now());
        return listingRepo.save(listing);
    }

    /** Published packages, optionally only those valid on {@code date}. Price is per person for min pax. */
    @Transactional(readOnly = true)
    public List<CatalogPackage> catalog(LocalDate date, String currency) {
        Long tenantId = TenantResolver.requireTenantId();
        List<CatalogPackage> out = new ArrayList<>();
        for (ProductPackageListing listing : listingRepo.findByTenantIdAndPublishedTrueOrderByIdAsc(tenantId)) {
            if (date != null && !validOn(listing, date)) {
                continue;
            }
            Optional<Product> product = productRepo.findByIdAndTenantId(listing.getProductId(), tenantId)
                    .filter(p -> p.getPackagePricing() != null);
            product.ifPresent(p -> out.add(toCatalog(tenantId, p, listing, date != null ? date : LocalDate.now(), currency)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public CatalogPackage catalogPackage(Long productId, LocalDate date, String currency) {
        Long tenantId = TenantResolver.requireTenantId();
        Product product = requirePackage(tenantId, productId);
        ProductPackageListing listing = requirePublished(tenantId, product.getId());
        return toCatalog(tenantId, product, listing, date != null ? date : LocalDate.now(), currency);
    }

    /**
     * Spaces that seat {@code pax} in the listing's setup and are free for the package window on {@code date}.
     */
    @Transactional(readOnly = true)
    public PackageAvailability availability(Long productId, LocalDate date, Integer pax, LocalTime startTime, String currency) {
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        if (pax == null || pax <= 0) {
            throw new IllegalArgumentException("pax must be > 0");
        }
        Long tenantId = TenantResolver.requireTenantId();
        Product product = requirePackage(tenantId, productId);
        ProductPackageListing listing = requirePublished(tenantId, product.getId());
        if (!validOn(listing, date)) {
            throw new IllegalArgumentException(displayName(product, listing) + " is not offered on " + date);
        }
        if (listing.getMinPax() != null && pax < listing.getMinPax()
                || listing.getMaxPax() != null && pax > listing.getMaxPax()) {
            throw new IllegalArgumentException(displayName(product, listing) + " is for " + paxRange(listing) + " persons");
        }
        List<ProductComponent> components = componentRepo.findByTenantIdAndPackageProductIdOrderByDisplayOrderAscIdAsc(
                tenantId, product.getId());
        LocalDateTime start = date.atTime(startTime != null ? startTime : listing.getDefaultStartTime());
        LocalDateTime[] window = spaceWindow(start, listing.getDuration(), components);
        PackageService.PackageQuote quote = packageService.quote(tenantId, product.getId(), date, pax, currency);
        List<ResourceSetupCapacityService.SpaceCandidate> spaces = spaceService.searchSpaces(
                        pax, listing.getSetupStyle(), window[0], window[1]).stream()
                .filter(ResourceSetupCapacityService.SpaceCandidate::available)
                .toList();
        return new PackageAvailability(product.getId(), displayName(product, listing), date, pax, window[0], window[1],
                quote.pricePerPerson(), quote.packageTotal(), !spaces.isEmpty(), spaces);
    }

    /**
     * The window the space is held: span of components that generate functions, otherwise the listing duration.
     */
    static LocalDateTime[] spaceWindow(LocalDateTime start, ProductPackageListing.Duration duration,
                                       List<ProductComponent> components) {
        int from = Integer.MAX_VALUE;
        int to = 0;
        for (ProductComponent c : components) {
            if (c.getFunctionType() == null || c.getDurationMinutes() == null) {
                continue;
            }
            int offset = c.getStartOffsetMinutes() != null ? c.getStartOffsetMinutes() : 0;
            from = Math.min(from, offset);
            to = Math.max(to, offset + c.getDurationMinutes());
        }
        if (to > 0) {
            return new LocalDateTime[]{start.plusMinutes(from), start.plusMinutes(to)};
        }
        if (duration == ProductPackageListing.Duration.CUSTOM) {
            throw new IllegalStateException("A CUSTOM package needs components with function type and duration");
        }
        int minutes = duration == ProductPackageListing.Duration.HALF_DAY ? HALF_DAY_MINUTES : FULL_DAY_MINUTES;
        return new LocalDateTime[]{start, start.plusMinutes(minutes)};
    }

    static boolean validOn(ProductPackageListing listing, LocalDate date) {
        return (listing.getValidFrom() == null || !date.isBefore(listing.getValidFrom()))
                && (listing.getValidTo() == null || !date.isAfter(listing.getValidTo()));
    }

    private CatalogPackage toCatalog(Long tenantId, Product product, ProductPackageListing listing, LocalDate date, String currency) {
        List<ProductComponent> components = componentRepo.findByTenantIdAndPackageProductIdOrderByDisplayOrderAscIdAsc(
                tenantId, product.getId());
        Map<Long, Product> products = productRepo.findAllById(
                        components.stream().map(ProductComponent::getComponentProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<CatalogComponent> parts = components.stream()
                .map(c -> new CatalogComponent(
                        products.containsKey(c.getComponentProductId()) ? products.get(c.getComponentProductId()).getName() : null,
                        c.getQty(), c.getQtyBasis(), c.getIncluded(),
                        c.getFunctionType() != null ? c.getFunctionType().name() : null,
                        c.getStartOffsetMinutes(), c.getDurationMinutes()))
                .toList();
        BigDecimal pricePerPerson = null;
        try {
            int pax = listing.getMinPax() != null ? listing.getMinPax() : 1;
            pricePerPerson = packageService.quote(tenantId, product.getId(), date, pax, currency).pricePerPerson();
        } catch (IllegalArgumentException ignored) {
            // no price on that date: listed without a price
        }
        List<String> images = product.getImages() == null ? List.of()
                : product.getImages().stream().map(ProductImage::getImageUrl).toList();
        return new CatalogPackage(product.getId(), displayName(product, listing),
                listing.getPublicDescription() != null ? listing.getPublicDescription() : product.getDescription(),
                product.getDefaultImageUrl(), images, listing.getValidFrom(), listing.getValidTo(),
                listing.getMinPax(), listing.getMaxPax(), listing.getDuration(), listing.getDefaultStartTime(),
                listing.getSetupStyle(), pricePerPerson, currency == null ? "EUR" : currency, parts);
    }

    private ProductPackageListing requirePublished(Long tenantId, Long productId) {
        return listingRepo.findByTenantIdAndProductId(tenantId, productId)
                .filter(l -> Boolean.TRUE.equals(l.getPublished()))
                .orElseThrow(() -> new IllegalArgumentException("Package is not published: " + productId));
    }

    private Product requirePackage(Long tenantId, Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("productId is required");
        }
        Product product = productRepo.findByIdAndTenantId(productId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        if (product.getPackagePricing() == null) {
            throw new IllegalArgumentException(product.getName() + " is not a package");
        }
        return product;
    }

    private static ProductPackageListing defaults(Long tenantId, Long productId) {
        return ProductPackageListing.builder()
                .tenantId(tenantId)
                .productId(productId)
                .duration(ProductPackageListing.Duration.FULL_DAY)
                .defaultStartTime(LocalTime.of(9, 0))
                .published(false)
                .build();
    }

    private static String displayName(Product product, ProductPackageListing listing) {
        return listing.getPublicName() != null ? listing.getPublicName() : product.getName();
    }

    private static String paxRange(ProductPackageListing listing) {
        if (listing.getMinPax() != null && listing.getMaxPax() != null) {
            return listing.getMinPax() + "-" + listing.getMaxPax();
        }
        return listing.getMinPax() != null ? "at least " + listing.getMinPax() : "at most " + listing.getMaxPax();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CatalogComponent(String name,
                                   Integer qty,
                                   ProductComponent.QtyBasis qtyBasis,
                                   Boolean included,
                                   String functionType,
                                   Integer startOffsetMinutes,
                                   Integer durationMinutes) {
    }

    public record CatalogPackage(Long productId,
                                 String name,
                                 String description,
                                 String imageUrl,
                                 List<String> images,
                                 LocalDate validFrom,
                                 LocalDate validTo,
                                 Integer minPax,
                                 Integer maxPax,
                                 ProductPackageListing.Duration duration,
                                 LocalTime defaultStartTime,
                                 com.stackwizard.booking_api.model.ResourceSetupCapacity.SetupStyle setupStyle,
                                 BigDecimal pricePerPerson,
                                 String currency,
                                 List<CatalogComponent> components) {
    }

    public record PackageAvailability(Long productId,
                                      String name,
                                      LocalDate date,
                                      int pax,
                                      LocalDateTime startsAt,
                                      LocalDateTime endsAt,
                                      BigDecimal pricePerPerson,
                                      BigDecimal total,
                                      boolean available,
                                      List<ResourceSetupCapacityService.SpaceCandidate> spaces) {
    }
}
