package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ResourceSetupCapacity;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventFunctionServiceTest {

    @Mock EventService eventService;
    @Mock EventFunctionRepository functionRepo;
    @Mock EventFunctionItemRepository itemRepo;
    @Mock ProductRepository productRepo;
    @Mock EventReservationSync reservationSync;
    @Mock ResourceSetupCapacityService capacityService;
    @Mock EventItemPricing itemPricing;

    EventFunctionService service;

    static final LocalDateTime NINE = LocalDateTime.of(2026, 11, 10, 9, 0);

    @BeforeEach
    void setUp() {
        service = new EventFunctionService(eventService, functionRepo, itemRepo, productRepo, reservationSync,
                capacityService, itemPricing);
    }

    private Event event() {
        return Event.builder().id(5L).tenantId(1L).status(Event.Status.INQUIRY).currency("EUR")
                .dateFrom(LocalDate.of(2026, 11, 10)).dateTo(LocalDate.of(2026, 11, 11))
                .expectedPax(80).build();
    }

    private EventFunction plenary(Integer pax) {
        return EventFunction.builder().resourceId(3L).functionType(EventFunction.FunctionType.PLENARY)
                .setupStyle(ResourceSetupCapacity.SetupStyle.THEATRE)
                .startsAt(NINE).endsAt(NINE.plusHours(8)).pax(pax).build();
    }

    @Test
    void occupancyDefaultsToEventWindow() {
        when(capacityService.capacityFor(1L, 3L, ResourceSetupCapacity.SetupStyle.THEATRE)).thenReturn(Optional.of(200));
        EventFunction function = plenary(150);

        service.normalizeAndValidate(event(), function);

        assertThat(function.getOccupancyStartsAt()).isEqualTo(NINE);
        assertThat(function.getOccupancyEndsAt()).isEqualTo(NINE.plusHours(8));
    }

    @Test
    void paxAboveSetupCapacityIsRejected() {
        when(capacityService.capacityFor(1L, 3L, ResourceSetupCapacity.SetupStyle.THEATRE)).thenReturn(Optional.of(120));

        assertThatThrownBy(() -> service.normalizeAndValidate(event(), plenary(150)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seats 120");
    }

    @Test
    void spaceWithoutSetupIsRejected() {
        when(capacityService.capacityFor(1L, 3L, ResourceSetupCapacity.SetupStyle.THEATRE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.normalizeAndValidate(event(), plenary(50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no THEATRE setup");
    }

    @Test
    void occupancyMustCoverEventWindow() {
        EventFunction function = plenary(null);
        function.setOccupancyStartsAt(NINE.plusMinutes(30));
        function.setOccupancyEndsAt(NINE.plusHours(9));

        assertThatThrownBy(() -> service.normalizeAndValidate(event(), function))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Occupancy");
    }

    @Test
    void functionOutsideEventDatesIsRejected() {
        EventFunction function = plenary(null);
        function.setSetupStyle(null);
        function.setStartsAt(NINE.plusDays(5));
        function.setEndsAt(NINE.plusDays(5).plusHours(2));

        assertThatThrownBy(() -> service.normalizeAndValidate(event(), function))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the event dates");
    }

    @Test
    void perPaxItemTakesBillablePaxAndCatalogPrice() {
        Product coffee = Product.builder().id(20L).tenantId(1L).name("Coffee break").defaultUom("UNIT").build();
        when(productRepo.findByIdAndTenantId(20L, 1L)).thenReturn(Optional.of(coffee));
        when(itemPricing.catalogPrice(eq(coffee), eq("UNIT"), eq("EUR"), eq(1L), any())).thenReturn(Optional.of(new BigDecimal("8.50")));
        EventFunction function = plenary(80);
        function.setOccupancyStartsAt(NINE);
        function.setOccupancyEndsAt(NINE.plusHours(8));
        EventFunctionItem item = EventFunctionItem.builder().productId(20L)
                .qtyBasis(EventFunctionItem.QtyBasis.PER_GUARANTEED_PAX)
                .serveAt(NINE.plusMinutes(90)).build();

        service.normalizeAndPriceItem(event(), function, item, true);

        assertThat(item.getQty()).isEqualTo(80);
        assertThat(item.getUnitPrice()).isEqualByComparingTo("8.50");
        assertThat(item.getGrossAmount()).isEqualByComparingTo("680.00");
    }

    @Test
    void serveAtOutsideFunctionIsRejected() {
        Product coffee = Product.builder().id(20L).tenantId(1L).name("Coffee break").defaultUom("UNIT").build();
        when(productRepo.findByIdAndTenantId(20L, 1L)).thenReturn(Optional.of(coffee));
        EventFunctionItem item = EventFunctionItem.builder().productId(20L).qty(10).unitPrice(BigDecimal.ONE)
                .serveAt(NINE.minusHours(1)).build();

        assertThatThrownBy(() -> service.normalizeAndPriceItem(event(), plenary(80), item, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the function");
    }

    @Test
    void packageCannotBeAddedAsItem() {
        Product ddr = Product.builder().id(30L).tenantId(1L).name("DDR").defaultUom("UNIT")
                .packagePricing(Product.PackagePricing.SPLIT_PERCENT).build();
        when(productRepo.findByIdAndTenantId(30L, 1L)).thenReturn(Optional.of(ddr));

        assertThatThrownBy(() -> service.normalizeAndPriceItem(event(), plenary(80),
                EventFunctionItem.builder().productId(30L).qty(1).build(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Packages are applied");
    }
}
