package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ProductVisibilityDtos;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductProperty;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.ProductPropertyRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductVisibilityServiceTest {
    ProductRepository productRepo = mock(ProductRepository.class);
    ProductPropertyRepository propertyRepo = mock(ProductPropertyRepository.class);
    PlatformTenantMappingRepository mappingRepo = mock(PlatformTenantMappingRepository.class);
    ProductVisibilityService service = new ProductVisibilityService(productRepo, propertyRepo, mappingRepo);

    Product product = Product.builder().id(10L).tenantId(1L).name("Coffee break")
            .propertyVisibility(Product.PropertyVisibility.ALL).build();

    @BeforeEach
    void setUp() {
        TenantContext.setScope(1L, 2L);
        when(productRepo.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(product));
        when(productRepo.findByIdAndTenantId(99L, 1L)).thenReturn(Optional.empty());
        when(mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(1L))
                .thenReturn(List.of(hotel(2L, "AA"), hotel(3L, "DH")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void modeAllOffersEveryHotelUnlessSwitchedOff() {
        when(propertyRepo.findByProductIdAndTenantIdIn(eq(10L), anyCollection()))
                .thenReturn(List.of(row(3L, false)));

        ProductVisibilityDtos.Response response = service.get(10L);

        assertThat(response.properties()).extracting(ProductVisibilityDtos.PropertyVisibility::visible)
                .containsExactly(true, false);
        assertThat(response.properties()).extracting(ProductVisibilityDtos.PropertyVisibility::explicit)
                .containsExactly(false, true);
    }

    @Test
    void modeSelectedOffersOnlySwitchedOnHotels() {
        product.setPropertyVisibility(Product.PropertyVisibility.SELECTED);
        when(propertyRepo.findByProductIdAndTenantIdIn(eq(10L), anyCollection())).thenReturn(List.of(row(2L, true)));

        assertThat(service.get(10L).properties())
                .extracting(ProductVisibilityDtos.PropertyVisibility::visible).containsExactly(true, false);
    }

    @Test
    void isVisibleFollowsModeAndSwitch() {
        when(propertyRepo.findByProductIdAndTenantIdIn(eq(10L), anyCollection())).thenReturn(List.of());
        assertThat(service.isVisible(product, 2L)).isTrue();

        product.setPropertyVisibility(Product.PropertyVisibility.SELECTED);
        assertThat(service.isVisible(product, 2L)).isFalse();

        when(propertyRepo.findByProductIdAndTenantIdIn(eq(10L), anyCollection())).thenReturn(List.of(row(2L, true)));
        assertThat(service.isVisible(product, 2L)).isTrue();
        assertThat(service.isVisible(product, null)).isFalse();
    }

    @Test
    void requireVisibleNamesTheProduct() {
        product.setPropertyVisibility(Product.PropertyVisibility.SELECTED);
        when(propertyRepo.findByProductIdAndTenantIdIn(eq(10L), anyCollection())).thenReturn(List.of());

        assertThatThrownBy(() -> service.requireVisible(product, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Coffee break");
    }

    @Test
    void updateReplacesSwitchesOfTheChainHotels() {
        ProductVisibilityDtos.Request request = new ProductVisibilityDtos.Request(
                Product.PropertyVisibility.SELECTED,
                List.of(new ProductVisibilityDtos.PropertySwitch(3L, true)));

        service.update(10L, request);

        assertThat(product.getPropertyVisibility()).isEqualTo(Product.PropertyVisibility.SELECTED);
        verify(propertyRepo).deleteByProductIdAndTenantIdIn(10L, Set.of(2L, 3L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductProperty>> saved = ArgumentCaptor.forClass(List.class);
        verify(propertyRepo).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(r -> {
            assertThat(r.getTenantId()).isEqualTo(3L);
            assertThat(r.getVisible()).isTrue();
        });
    }

    @Test
    void updateRejectsAHotelOfAnotherChain() {
        ProductVisibilityDtos.Request request = new ProductVisibilityDtos.Request(
                Product.PropertyVisibility.SELECTED,
                List.of(new ProductVisibilityDtos.PropertySwitch(77L, true)));

        assertThatThrownBy(() -> service.update(10L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
        verify(productRepo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void updateRejectsSwitchWithoutValue() {
        ProductVisibilityDtos.Request request = new ProductVisibilityDtos.Request(
                Product.PropertyVisibility.SELECTED,
                List.of(new ProductVisibilityDtos.PropertySwitch(2L, null)));

        assertThatThrownBy(() -> service.update(10L, request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productOfAnotherChainIsNotFound() {
        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(ResponseStatusException.class);
        verify(productRepo).findByIdAndTenantId(eq(99L), eq(1L));
        org.mockito.Mockito.verifyNoMoreInteractions(propertyRepo);
    }

    private static PlatformTenantMapping hotel(Long tenantId, String code) {
        return PlatformTenantMapping.builder().tenantId(tenantId).platformTenantId(UUID.randomUUID())
                .kind(PlatformTenantMapping.Kind.PROPERTY).parentTenantId(1L).hotelCode(code).name(code).build();
    }

    private static ProductProperty row(Long tenantId, boolean visible) {
        return ProductProperty.builder().tenantId(tenantId).productId(10L).visible(visible).build();
    }
}
