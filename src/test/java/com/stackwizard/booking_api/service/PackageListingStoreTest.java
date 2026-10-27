package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.ProductPackageListing;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.repository.CatalogItemRepository;
import com.stackwizard.booking_api.repository.SalesCatalogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /api/products/{id}/package-listing and /api/catalog/packages read and write catalog_item of catalog MICE. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PackageListingStoreTest {
    @Mock SalesCatalogRepository catalogRepo;
    @Mock CatalogItemRepository itemRepo;

    @Test
    void saveCreatesMiceCatalogAndPackageItem() {
        PackageListingStore store = new PackageListingStore(catalogRepo, itemRepo);
        when(catalogRepo.findByTenantIdAndCodeIgnoreCase(2L, "MICE")).thenReturn(List.of());
        when(catalogRepo.save(any())).thenAnswer(inv -> {
            SalesCatalog c = inv.getArgument(0);
            c.setId(7L);
            return c;
        });
        when(itemRepo.save(any())).thenAnswer(inv -> {
            CatalogItem i = inv.getArgument(0);
            i.setId(70L);
            return i;
        });

        ProductPackageListing saved = store.save(ProductPackageListing.builder().tenantId(2L).productId(85L)
                .publicName("Day Delegate Package").minPax(10).maxPax(120).duration(ProductPackageListing.Duration.FULL_DAY)
                .defaultStartTime(LocalTime.of(9, 0)).setupStyle("THEATRE").published(true).build());

        ArgumentCaptor<SalesCatalog> catalog = ArgumentCaptor.forClass(SalesCatalog.class);
        verify(catalogRepo).save(catalog.capture());
        assertThat(catalog.getValue().getSaleMode()).isEqualTo(SalesCatalog.SaleMode.INQUIRY);
        assertThat(catalog.getValue().getPropertyTenantId()).isNull();
        assertThat(saved.getId()).isEqualTo(70L);
        assertThat(saved.getPublicName()).isEqualTo("Day Delegate Package");
        assertThat(saved.getPublished()).isTrue();
        ArgumentCaptor<CatalogItem> item = ArgumentCaptor.forClass(CatalogItem.class);
        verify(itemRepo).save(item.capture());
        assertThat(item.getValue().getItemKind()).isEqualTo(CatalogItem.Kind.PACKAGE);
        assertThat(item.getValue().getItemGroup()).isEqualTo("PACKAGE");
        assertThat(item.getValue().getCatalogId()).isEqualTo(7L);
    }

    @Test
    void publishedListingsComeFromChainMiceCatalog() {
        PackageListingStore store = new PackageListingStore(catalogRepo, itemRepo);
        when(catalogRepo.findByTenantIdAndCodeIgnoreCase(2L, "MICE")).thenReturn(List.of(
                SalesCatalog.builder().id(8L).propertyTenantId(3L).build(), SalesCatalog.builder().id(7L).build()));
        when(itemRepo.findForCatalog(7L)).thenReturn(List.of(
                CatalogItem.builder().id(2L).productId(85L).itemKind(CatalogItem.Kind.PACKAGE).published(true).build(),
                CatalogItem.builder().id(3L).productId(86L).itemKind(CatalogItem.Kind.PACKAGE).published(false).build(),
                CatalogItem.builder().id(4L).resourceId(1L).itemKind(CatalogItem.Kind.RESOURCE).published(true).build()));
        when(itemRepo.findByCatalogIdAndProductId(7L, 86L)).thenReturn(Optional.of(
                CatalogItem.builder().id(3L).productId(86L).itemKind(CatalogItem.Kind.PACKAGE).published(false).build()));

        assertThat(store.findByTenantIdAndPublishedTrueOrderByIdAsc(2L)).extracting(ProductPackageListing::getProductId)
                .containsExactly(85L);
        ProductPackageListing draft = store.findByTenantIdAndProductId(2L, 86L).orElseThrow();
        assertThat(draft.getDuration()).isEqualTo(ProductPackageListing.Duration.FULL_DAY);
        assertThat(draft.getDefaultStartTime()).isEqualTo(LocalTime.of(9, 0));
    }
}
