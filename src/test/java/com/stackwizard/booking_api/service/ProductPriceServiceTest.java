package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ProductPriceDtos;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.PriceProfile;
import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.PriceProfileDateRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmRole;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductPriceServiceTest {

    @Mock PriceListEntryRepository priceRepo;
    @Mock PriceProfileDateRepository periodRepo;
    @Mock ProductRepository productRepo;
    @Mock AuthUserAccessor authUserAccessor;
    @Mock CrmAccessContext accessContext;

    ProductPriceService service;
    PriceProfile profile = PriceProfile.builder().id(1L).tenantId(2L).name("Events EUR").currency("EUR").build();
    PriceProfileDate season = PriceProfileDate.builder().id(10L).priceProfile(profile)
            .dateFrom(LocalDate.of(2026, 1, 1)).dateTo(LocalDate.of(2026, 12, 31)).build();
    PriceProfileDate nextSeason = PriceProfileDate.builder().id(11L).priceProfile(profile)
            .dateFrom(LocalDate.of(2027, 1, 1)).dateTo(LocalDate.of(2027, 12, 31)).build();
    List<PriceListEntry> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(2L);
        service = new ProductPriceService(priceRepo, periodRepo, productRepo, authUserAccessor, accessContext);
        Product product = Product.builder().id(5L).tenantId(2L).name("Hall A rent").defaultUom("DAY")
                .extraUoms(Set.of("HOUR")).build();
        when(productRepo.findByIdAndTenantId(5L, 2L)).thenReturn(Optional.of(product));
        when(periodRepo.findForTenant(2L)).thenReturn(List.of(season, nextSeason));
        when(priceRepo.findForProduct(5L, 2L)).thenAnswer(inv -> List.copyOf(stored));
        when(authUserAccessor.requireAppUser()).thenReturn(AppUser.builder().id(7L).role(AppUser.Role.ADMIN).build());
        when(accessContext.crmRoles()).thenReturn(Set.of());
        stored.add(PriceListEntry.builder().id(100L).productId(5L).uom("DAY").price(new BigDecimal("1000.00"))
                .priceProfile(profile).priceProfileDate(season).build());
        stored.add(PriceListEntry.builder().id(101L).productId(5L).uom("HOUR").price(new BigDecimal("150.00"))
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(12, 0))
                .priceProfile(profile).priceProfileDate(season).build());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rejectsUomTheProductDoesNotOffer() {
        var input = new ProductPriceDtos.PriceInput(null, 10L, "night", new BigDecimal("50"), null, null);
        assertThatThrownBy(() -> service.save(5L, new ProductPriceDtos.SaveRequest(List.of(input), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NIGHT");
    }

    @Test
    void rejectsDuplicateSlotInSamePeriod() {
        var input = new ProductPriceDtos.PriceInput(null, 10L, "day", new BigDecimal("900"), null, null);
        assertThatThrownBy(() -> service.save(5L, new ProductPriceDtos.SaveRequest(List.of(input), List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void addsPriceToAnotherPeriodWithProfileFromPeriod() {
        var input = new ProductPriceDtos.PriceInput(null, 11L, "day", new BigDecimal("1100.456"), null, null);
        service.save(5L, new ProductPriceDtos.SaveRequest(List.of(input), List.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PriceListEntry>> saved = ArgumentCaptor.forClass(List.class);
        verify(priceRepo).saveAll(saved.capture());
        PriceListEntry entry = saved.getValue().get(0);
        assertThat(entry.getPriceProfile()).isSameAs(profile);
        assertThat(entry.getPrice()).isEqualByComparingTo("1100.46");
        assertThat(entry.getUom()).isEqualTo("DAY");
    }

    @Test
    void adjustRaisesSelectedPricesAndRounds() {
        service.adjust(5L, new ProductPriceDtos.AdjustRequest(new BigDecimal("7"), List.of(10L), "HOUR", new BigDecimal("5")));

        assertThat(stored.get(1).getPrice()).isEqualByComparingTo("160.00");
        assertThat(stored.get(0).getPrice()).isEqualByComparingTo("1000.00");
    }

    @Test
    void copyToNextSeasonAppliesPercent() {
        service.copy(5L, new ProductPriceDtos.CopyRequest(10L, 11L, new BigDecimal("10"), null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PriceListEntry>> saved = ArgumentCaptor.forClass(List.class);
        verify(priceRepo).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue()).allMatch(e -> e.getPriceProfileDate() == nextSeason);
        assertThat(saved.getValue().get(0).getPrice()).isEqualByComparingTo("1100.00");
        assertThat(saved.getValue().get(1).getStartTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void salesStaffCannotChangePrices() {
        when(authUserAccessor.requireAppUser()).thenReturn(AppUser.builder().id(8L).role(AppUser.Role.STAFF).build());
        when(accessContext.crmRoles()).thenReturn(Set.of(CrmRole.SALES_REP));

        assertThatThrownBy(() -> service.adjust(5L, new ProductPriceDtos.AdjustRequest(BigDecimal.TEN, null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void roundToHalves() {
        assertThat(ProductPriceService.round(new BigDecimal("12.74"), new BigDecimal("0.5"))).isEqualByComparingTo("12.50");
        assertThat(ProductPriceService.round(new BigDecimal("12.76"), new BigDecimal("0.5"))).isEqualByComparingTo("13.00");
    }
}
