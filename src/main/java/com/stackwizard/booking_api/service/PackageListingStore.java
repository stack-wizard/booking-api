package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.ProductPackageListing;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.repository.CatalogItemRepository;
import com.stackwizard.booking_api.repository.SalesCatalogRepository;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Web listing of packages ({@code /api/products/{id}/package-listing}, {@code /api/catalog/packages}) stored as
 * {@code catalog_item} of kind PACKAGE in the chain's {@code MICE} catalog. {@code product_package_listing} is no longer
 * read or written; it stays only until the CMS has moved to {@code /api/catalogs}. The listing id is the item id.
 */
@Component
public class PackageListingStore {
    public static final String MICE = "MICE";

    private final SalesCatalogRepository catalogRepo;
    private final CatalogItemRepository itemRepo;

    public PackageListingStore(SalesCatalogRepository catalogRepo, CatalogItemRepository itemRepo) {
        this.catalogRepo = catalogRepo;
        this.itemRepo = itemRepo;
    }

    public Optional<ProductPackageListing> findByTenantIdAndProductId(Long tenantId, Long productId) {
        return miceCatalog(tenantId)
                .flatMap(c -> itemRepo.findByCatalogIdAndProductId(c.getId(), productId))
                .filter(i -> i.getItemKind() == CatalogItem.Kind.PACKAGE)
                .map(PackageListingStore::toListing);
    }

    public List<ProductPackageListing> findByTenantIdAndPublishedTrueOrderByIdAsc(Long tenantId) {
        return miceCatalog(tenantId)
                .map(c -> itemRepo.findForCatalog(c.getId()).stream()
                        .filter(i -> i.getItemKind() == CatalogItem.Kind.PACKAGE && Boolean.TRUE.equals(i.getPublished()))
                        .sorted(Comparator.comparing(CatalogItem::getId))
                        .map(PackageListingStore::toListing)
                        .toList())
                .orElse(List.of());
    }

    /** Creates the MICE catalog (INQUIRY, EXTERNAL) and the package item when missing. */
    public ProductPackageListing save(ProductPackageListing listing) {
        Long tenantId = listing.getTenantId();
        SalesCatalog catalog = miceCatalog(tenantId).orElseGet(() -> catalogRepo.save(SalesCatalog.builder()
                .tenantId(tenantId)
                .code(MICE)
                .name(MICE)
                .saleMode(SalesCatalog.SaleMode.INQUIRY)
                .requestType(ReservationRequest.Type.EXTERNAL)
                .currency("EUR")
                .active(true)
                .build()));
        CatalogItem item = itemRepo.findByCatalogIdAndProductId(catalog.getId(), listing.getProductId())
                .orElseGet(() -> CatalogItem.builder()
                        .tenantId(tenantId)
                        .catalogId(catalog.getId())
                        .productId(listing.getProductId())
                        .itemGroup(SalesCatalogService.GROUP_PACKAGE)
                        .displayOrder(0)
                        .build());
        item.setItemKind(CatalogItem.Kind.PACKAGE);
        item.setResourceId(null);
        item.setPublicName(listing.getPublicName());
        item.setPublicDescription(listing.getPublicDescription());
        item.setValidFrom(listing.getValidFrom());
        item.setValidTo(listing.getValidTo());
        item.setMinPax(listing.getMinPax());
        item.setMaxPax(listing.getMaxPax());
        item.setDuration(listing.getDuration());
        item.setDefaultStartTime(listing.getDefaultStartTime());
        item.setSetupStyle(listing.getSetupStyle());
        item.setPublished(Boolean.TRUE.equals(listing.getPublished()));
        item.setUpdatedAt(listing.getUpdatedAt() != null ? listing.getUpdatedAt() : OffsetDateTime.now());
        return toListing(itemRepo.save(item));
    }

    public Optional<SalesCatalog> miceCatalog(Long tenantId) {
        return catalogRepo.findByTenantIdAndCodeIgnoreCase(tenantId, MICE).stream()
                .filter(c -> c.getPropertyTenantId() == null)
                .min(Comparator.comparing(SalesCatalog::getId));
    }

    static ProductPackageListing toListing(CatalogItem item) {
        return ProductPackageListing.builder()
                .id(item.getId())
                .tenantId(item.getTenantId())
                .productId(item.getProductId())
                .publicName(item.getPublicName())
                .publicDescription(item.getPublicDescription())
                .validFrom(item.getValidFrom())
                .validTo(item.getValidTo())
                .minPax(item.getMinPax())
                .maxPax(item.getMaxPax())
                .duration(item.getDuration() != null ? item.getDuration() : ProductPackageListing.Duration.FULL_DAY)
                .defaultStartTime(item.getDefaultStartTime() != null ? item.getDefaultStartTime() : LocalTime.of(9, 0))
                .setupStyle(item.getSetupStyle())
                .published(item.getPublished())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }
}
