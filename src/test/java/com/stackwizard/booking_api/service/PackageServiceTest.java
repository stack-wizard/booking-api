package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.repository.ProductComponentRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PackageServiceTest {

    @Mock ProductRepository productRepo;
    @Mock ProductComponentRepository componentRepo;
    @Mock PriceListEntryResolver priceResolver;

    PackageService service;

    static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    Product ddr = product(1L, "Day Delegate Rate", new BigDecimal("13"));
    Product room = product(2L, "Room hire", new BigDecimal("25"));
    Product lunch = product(3L, "Lunch", new BigDecimal("13"));
    Product coffee = product(4L, "Coffee break", new BigDecimal("13"));

    static Product product(Long id, String name, BigDecimal tax) {
        return Product.builder().id(id).tenantId(1L).name(name).defaultUom("UNIT")
                .tax1Percent(tax).tax2Percent(BigDecimal.ZERO).build();
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        service = new PackageService(productRepo, componentRepo, priceResolver);
        for (Product p : List.of(ddr, room, lunch, coffee)) {
            when(productRepo.findByIdAndTenantId(p.getId(), 1L)).thenReturn(Optional.of(p));
        }
        when(productRepo.findAllById(anyList())).thenReturn(List.of(room, lunch, coffee));
        price(ddr, "60");
        price(room, "30");
        price(lunch, "25");
        price(coffee, "8");
        when(componentRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void price(Product product, String value) {
        PriceListEntry entry = new PriceListEntry();
        entry.setPrice(new BigDecimal(value));
        when(priceResolver.findEffectiveForProductUomOnDate(eq(product.getId()), eq("UNIT"), any(), eq(1L), eq(DAY), any()))
                .thenReturn(List.of(entry));
    }

    private ProductComponent component(Product product, String share, String fixed, boolean included) {
        return ProductComponent.builder().componentProductId(product.getId()).qty(1)
                .qtyBasis(ProductComponent.QtyBasis.PER_PAX).included(included)
                .sharePercent(share != null ? new BigDecimal(share) : null)
                .fixedAmount(fixed != null ? new BigDecimal(fixed) : null)
                .build();
    }

    private void stored(Product.PackagePricing pricing, ProductComponent... components) {
        ddr.setPackagePricing(pricing);
        for (ProductComponent c : components) {
            c.setTenantId(1L);
            c.setPackageProductId(ddr.getId());
        }
        when(componentRepo.findByTenantIdAndPackageProductIdOrderByDisplayOrderAscIdAsc(1L, 1L)).thenReturn(List.of(components));
    }

    @Test
    void splitPercentMustAddUpTo100() {
        assertThatThrownBy(() -> service.define(1L, Product.PackagePricing.SPLIT_PERCENT, List.of(
                component(room, "50", null, true), component(lunch, "40", null, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100%");
    }

    @Test
    void nestedPackageIsRejected() {
        lunch.setPackagePricing(Product.PackagePricing.SUM);

        assertThatThrownBy(() -> service.define(1L, Product.PackagePricing.SUM, List.of(component(lunch, null, null, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nested");
    }

    @Test
    void sumAddsComponentCatalogPrices() {
        stored(Product.PackagePricing.SUM, component(room, null, null, true), component(lunch, null, null, true));

        PackageService.PackageQuote quote = service.quote(1L, DAY, 10, "EUR");

        assertThat(quote.pricePerPerson()).isEqualByComparingTo("55.00");
        assertThat(quote.packageTotal()).isEqualByComparingTo("550.00");
        assertThat(quote.components()).extracting(PackageService.ComponentQuote::amount)
                .containsExactly(new BigDecimal("300.00"), new BigDecimal("250.00"));
    }

    @Test
    void splitPercentDistributesPackagePriceAndKeepsComponentTax() {
        stored(Product.PackagePricing.SPLIT_PERCENT,
                component(room, "50", null, true), component(lunch, "35", null, true), component(coffee, "15", null, true));

        PackageService.PackageQuote quote = service.quote(1L, DAY, 10, "EUR");

        assertThat(quote.packageTotal()).isEqualByComparingTo("600.00");
        assertThat(quote.includedTotal()).isEqualByComparingTo("600.00");
        assertThat(quote.difference()).isEqualByComparingTo("0");
        PackageService.ComponentQuote roomLine = quote.components().getFirst();
        assertThat(roomLine.amount()).isEqualByComparingTo("300.00");
        assertThat(roomLine.unitPrice()).isEqualByComparingTo("30.00");
        assertThat(roomLine.tax1Percent()).isEqualByComparingTo("25");
    }

    @Test
    void splitFixedReportsDifferenceAndExtrasAreSeparate() {
        stored(Product.PackagePricing.SPLIT_FIXED,
                component(room, null, "30", true), component(lunch, null, "25", true), component(coffee, null, null, false));

        PackageService.PackageQuote quote = service.quote(1L, DAY, 2, "EUR");

        assertThat(quote.difference()).isEqualByComparingTo("-5.00");
        assertThat(quote.includedTotal()).isEqualByComparingTo("110.00");
        assertThat(quote.extrasTotal()).isEqualByComparingTo("16.00");
    }
}
