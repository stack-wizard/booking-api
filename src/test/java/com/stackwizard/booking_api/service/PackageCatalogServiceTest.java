package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.model.ProductPackageListing;
import com.stackwizard.booking_api.model.ResourceSetupCapacity;
import com.stackwizard.booking_api.repository.ProductComponentRepository;
import com.stackwizard.booking_api.repository.ProductPackageListingRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PackageCatalogServiceTest {

    @Mock ProductRepository productRepo;
    @Mock ProductComponentRepository componentRepo;
    @Mock ProductPackageListingRepository listingRepo;
    @Mock PackageService packageService;
    @Mock ResourceSetupCapacityService spaceService;

    PackageCatalogService service;

    static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    Product ddr = Product.builder().id(1L).tenantId(1L).name("DDR").packagePricing(Product.PackagePricing.SPLIT_PERCENT).build();
    ProductPackageListing listing = ProductPackageListing.builder().id(5L).tenantId(1L).productId(1L)
            .published(true).minPax(10).maxPax(80).duration(ProductPackageListing.Duration.FULL_DAY)
            .defaultStartTime(LocalTime.of(9, 0)).setupStyle(ResourceSetupCapacity.SetupStyle.THEATRE)
            .validFrom(LocalDate.of(2026, 1, 1)).validTo(LocalDate.of(2026, 12, 31)).build();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        service = new PackageCatalogService(productRepo, componentRepo, listingRepo, packageService, spaceService);
        when(productRepo.findByIdAndTenantId(1L, 1L)).thenReturn(Optional.of(ddr));
        when(listingRepo.findByTenantIdAndProductId(1L, 1L)).thenReturn(Optional.of(listing));
        when(packageService.quote(eq(1L), eq(1L), any(), anyInt(), any())).thenReturn(new PackageService.PackageQuote(
                1L, "DDR", Product.PackagePricing.SPLIT_PERCENT, 20, new BigDecimal("65.00"), new BigDecimal("1300.00"),
                new BigDecimal("1300.00"), BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void spaceWindowSpansFunctionComponents() {
        LocalDateTime start = DAY.atTime(9, 0);
        List<ProductComponent> components = List.of(
                component(EventFunction.FunctionType.PLENARY, 0, 480),
                component(EventFunction.FunctionType.COFFEE_BREAK, 90, 30),
                component(null, null, null));
        LocalDateTime[] window = PackageCatalogService.spaceWindow(start, ProductPackageListing.Duration.HALF_DAY, components);
        assertThat(window[0]).isEqualTo(start);
        assertThat(window[1]).isEqualTo(DAY.atTime(17, 0));
    }

    @Test
    void spaceWindowFallsBackToDuration() {
        LocalDateTime start = DAY.atTime(13, 0);
        LocalDateTime[] window = PackageCatalogService.spaceWindow(start, ProductPackageListing.Duration.HALF_DAY, List.of());
        assertThat(window[1]).isEqualTo(DAY.atTime(17, 0));
        assertThatThrownBy(() -> PackageCatalogService.spaceWindow(start, ProductPackageListing.Duration.CUSTOM, List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void availabilityReturnsOnlyFreeSpacesForTheWindow() {
        when(componentRepo.findByTenantIdAndPackageProductIdOrderByDisplayOrderAscIdAsc(1L, 1L))
                .thenReturn(List.of(component(EventFunction.FunctionType.PLENARY, 0, 480)));
        ResourceSetupCapacityService.SpaceCandidate free = new ResourceSetupCapacityService.SpaceCandidate(
                10L, "Hall A", "MEETING_ROOM", null, null, null, List.of(), true);
        ResourceSetupCapacityService.SpaceCandidate busy = new ResourceSetupCapacityService.SpaceCandidate(
                11L, "Hall B", "MEETING_ROOM", null, null, null, List.of(), false);
        when(spaceService.searchSpaces(20, ResourceSetupCapacity.SetupStyle.THEATRE, DAY.atTime(9, 0), DAY.atTime(17, 0)))
                .thenReturn(List.of(free, busy));

        PackageCatalogService.PackageAvailability result = service.availability(1L, DAY, 20, null, "EUR");

        assertThat(result.available()).isTrue();
        assertThat(result.spaces()).extracting(ResourceSetupCapacityService.SpaceCandidate::resourceId).containsExactly(10L);
        assertThat(result.pricePerPerson()).isEqualByComparingTo("65.00");
    }

    @Test
    void availabilityRejectsPaxOutsideListingAndUnpublished() {
        assertThatThrownBy(() -> service.availability(1L, DAY, 5, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10-80");
        assertThatThrownBy(() -> service.availability(1L, LocalDate.of(2027, 2, 1), 20, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not offered");
        listing.setPublished(false);
        assertThatThrownBy(() -> service.availability(1L, DAY, 20, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not published");
    }

    @Test
    void catalogSkipsListingsOutsideValidity() {
        when(listingRepo.findByTenantIdAndPublishedTrueOrderByIdAsc(1L)).thenReturn(List.of(listing));
        when(componentRepo.findByTenantIdAndPackageProductIdOrderByDisplayOrderAscIdAsc(1L, 1L)).thenReturn(List.of());
        assertThat(service.catalog(DAY, null)).hasSize(1);
        assertThat(service.catalog(LocalDate.of(2027, 5, 1), null)).isEmpty();
    }

    private static ProductComponent component(EventFunction.FunctionType type, Integer offset, Integer duration) {
        return ProductComponent.builder().componentProductId(2L).qty(1).included(true)
                .qtyBasis(ProductComponent.QtyBasis.PER_PAX)
                .functionType(type).startOffsetMinutes(offset).durationMinutes(duration).build();
    }
}
