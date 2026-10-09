package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceSetupCapacity;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.TenantContext;
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
import java.util.List;
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
    @Mock ResourceRepository resourceRepo;
    @Mock ProductVisibilityService productVisibility;

    EventFunctionService service;

    static final LocalDateTime NINE = LocalDateTime.of(2026, 11, 10, 9, 0);

    @BeforeEach
    void setUp() {
        service = new EventFunctionService(eventService, functionRepo, itemRepo, productRepo, reservationSync,
                capacityService, itemPricing, resourceRepo, TenantHierarchyTestSupport.standalone(), productVisibility);
    }

    private Event event() {
        return Event.builder().id(5L).tenantId(1L).status(Event.Status.INQUIRY).currency("EUR")
                .dateFrom(LocalDate.of(2026, 11, 10)).dateTo(LocalDate.of(2026, 11, 11))
                .expectedPax(80).build();
    }

    private EventFunction plenary(Integer pax) {
        return EventFunction.builder().tenantId(1L).resourceId(3L).functionType(EventFunction.FunctionType.PLENARY)
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

    private EventFunctionService chainService(TenantHierarchy hierarchy) {
        return new EventFunctionService(eventService, functionRepo, itemRepo, productRepo, reservationSync,
                capacityService, itemPricing, resourceRepo, hierarchy, productVisibility);
    }

    @Test
    void spaceDecidesTheHotelOfTheFunction() {
        TenantHierarchy hierarchy = org.mockito.Mockito.mock(TenantHierarchy.class);
        when(hierarchy.isPropertyOf(2L, 1L)).thenReturn(true);
        when(resourceRepo.findById(3L)).thenReturn(Optional.of(Resource.builder().id(3L).tenantId(2L).build()));

        EventFunction function = plenary(10);
        function.setTenantId(null);

        assertThat(chainService(hierarchy).resolveProperty(event(), function)).isEqualTo(2L);
    }

    @Test
    void spaceOfAnotherChainIsRejected() {
        TenantHierarchy hierarchy = org.mockito.Mockito.mock(TenantHierarchy.class);
        when(hierarchy.isPropertyOf(99L, 1L)).thenReturn(false);
        when(resourceRepo.findById(3L)).thenReturn(Optional.of(Resource.builder().id(3L).tenantId(99L).build()));

        assertThatThrownBy(() -> chainService(hierarchy).resolveProperty(event(), plenary(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Space not found");
    }

    @Test
    void functionWithoutSpaceNeedsAHotelWhenTheChainHasSeveral() {
        TenantHierarchy hierarchy = org.mockito.Mockito.mock(TenantHierarchy.class);
        when(hierarchy.propertyIdsOf(1L)).thenReturn(List.of(2L, 3L));
        EventFunction function = EventFunction.builder().functionType(EventFunction.FunctionType.COFFEE_BREAK).build();
        TenantContext.clear();

        assertThatThrownBy(() -> chainService(hierarchy).resolveProperty(event(), function))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hotel");
    }

    @Test
    void functionWithoutSpaceUsesTheOnlyHotel() {
        TenantHierarchy hierarchy = org.mockito.Mockito.mock(TenantHierarchy.class);
        when(hierarchy.propertyIdsOf(1L)).thenReturn(List.of(2L));
        EventFunction function = EventFunction.builder().functionType(EventFunction.FunctionType.COFFEE_BREAK).build();
        TenantContext.clear();

        assertThat(chainService(hierarchy).resolveProperty(event(), function)).isEqualTo(2L);
    }

    @Test
    void itemWithProductHiddenInTheHotelIsRejected() {
        Product coffee = Product.builder().id(20L).tenantId(1L).name("Coffee break").defaultUom("UNIT").build();
        when(productRepo.findByIdAndTenantId(20L, 1L)).thenReturn(Optional.of(coffee));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Product Coffee break is not offered in this hotel"))
                .when(productVisibility).requireVisible(coffee, 1L);
        EventFunctionItem item = EventFunctionItem.builder().productId(20L).qty(1).build();

        assertThatThrownBy(() -> service.normalizeAndPriceItem(event(), plenary(80), item, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not offered");
    }

    @Test
    void movingAFunctionToAnotherHotelReleasesTheOldHoldAndMovesItsItems() {
        TenantHierarchy hierarchy = org.mockito.Mockito.mock(TenantHierarchy.class);
        when(hierarchy.isPropertyOf(3L, 1L)).thenReturn(true);
        when(resourceRepo.findById(7L)).thenReturn(Optional.of(Resource.builder().id(7L).tenantId(3L).build()));
        EventFunction existing = plenary(10);
        existing.setId(40L);
        existing.setEventId(5L);
        existing.setTenantId(2L);
        EventFunctionItem item = EventFunctionItem.builder().id(60L).tenantId(2L).eventFunctionId(40L).build();
        Event event = event();
        TenantContext.setScope(1L, 2L);
        try {
            when(functionRepo.findForOrgById(1L, 40L)).thenReturn(Optional.of(existing));
            when(eventService.requireEvent(5L)).thenReturn(event);
            when(eventService.requireEditable(5L)).thenReturn(event);
            when(itemRepo.findByTenantIdAndEventFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(2L, 40L))
                    .thenReturn(List.of(item));
            when(itemRepo.findByTenantIdAndEventFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(3L, 40L))
                    .thenReturn(List.of(item));
            when(functionRepo.save(existing)).thenReturn(existing);
            when(capacityService.capacityFor(3L, 7L, ResourceSetupCapacity.SetupStyle.THEATRE)).thenReturn(Optional.of(200));
            EventFunction changes = plenary(10);
            changes.setResourceId(7L);

            chainService(hierarchy).updateFunction(40L, changes);

            org.mockito.Mockito.verify(reservationSync).detachFunction(event, existing);
            assertThat(existing.getTenantId()).isEqualTo(3L);
            assertThat(item.getTenantId()).isEqualTo(3L);
            org.mockito.Mockito.verify(capacityService).requireSpaceResource(3L, 7L);
            org.mockito.Mockito.verify(reservationSync).syncFunction(event, existing);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void eventLimitedToAHotelRejectsASpaceOfAnotherHotel() {
        TenantHierarchy hierarchy = org.mockito.Mockito.mock(TenantHierarchy.class);
        when(hierarchy.isPropertyOf(3L, 1L)).thenReturn(true);
        when(resourceRepo.findById(3L)).thenReturn(Optional.of(Resource.builder().id(3L).tenantId(3L).build()));
        Event limited = event();
        limited.setPropertyTenantId(2L);
        EventFunction function = plenary(10);
        function.setTenantId(null);

        assertThatThrownBy(() -> chainService(hierarchy).resolveProperty(limited, function))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limited");
    }

    @Test
    void functionWithoutSpaceDefaultsToTheHotelOfALimitedEvent() {
        TenantHierarchy hierarchy = org.mockito.Mockito.mock(TenantHierarchy.class);
        Event limited = event();
        limited.setPropertyTenantId(2L);
        EventFunction function = EventFunction.builder().functionType(EventFunction.FunctionType.COFFEE_BREAK).build();
        TenantContext.setScope(1L, 3L);
        try {
            assertThat(chainService(hierarchy).resolveProperty(limited, function)).isEqualTo(2L);
        } finally {
            TenantContext.clear();
        }
    }
}
