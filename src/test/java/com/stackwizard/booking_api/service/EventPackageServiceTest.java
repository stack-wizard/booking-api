package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.EventDtos;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPackageServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2027, 4, 14, 9, 0);

    @Mock EventService eventService;
    @Mock EventFunctionService functionService;
    @Mock PackageService packageService;
    @Mock EventFunctionRepository functionRepo;
    @Mock EventFunctionItemRepository itemRepo;
    @Mock ProductRepository productRepo;
    @Mock EventReservationSync reservationSync;

    EventPackageService service;
    Event event;

    @BeforeEach
    void setUp() {
        service = new EventPackageService(eventService, functionService, packageService, functionRepo, itemRepo,
                productRepo, reservationSync);
        event = Event.builder().id(5L).tenantId(1L).status(Event.Status.INQUIRY).currency("EUR")
                .dateFrom(LocalDate.of(2027, 4, 14)).dateTo(LocalDate.of(2027, 4, 15)).guaranteedPax(120).build();
        when(eventService.requireEditable(5L)).thenReturn(event);
    }

    @Test
    void componentItemsLandOnTheFunctionsTheyGenerate() {
        stubSaves();
        when(productRepo.findByIdAndTenantId(anyLong(), eq(1L)))
                .thenAnswer(inv -> Optional.of(Product.builder().id(inv.getArgument(0)).name("P" + inv.getArgument(0))
                        .defaultUom("UNIT").build()));
        when(packageService.quote(1L, 50L, START.toLocalDate(), 120, "EUR")).thenReturn(quote(Product.PackagePricing.SPLIT_PERCENT,
                BigDecimal.ZERO,
                line(component(101L, EventFunction.FunctionType.PLENARY, 0, 480), "Main Hall rent", 120),
                line(component(102L, EventFunction.FunctionType.COFFEE_BREAK, 90, 30), "Coffee break", 240),
                line(component(103L, EventFunction.FunctionType.LUNCH, 210, 60), "Business lunch", 120)));

        List<EventFunction> functions = service.apply(5L, new EventDtos.ApplyPackageRequest(50L, 9L, null, START, 120));

        assertThat(functions).extracting(EventFunction::getName)
                .containsExactly("Day Delegate Rate", "Coffee break", "Business lunch");
        assertThat(functions).extracting(EventFunction::getResourceId).containsExactly(9L, null, null);
        assertThat(functions.get(1).getStartsAt()).isEqualTo(START.plusMinutes(90));

        ArgumentCaptor<EventFunctionItem> items = ArgumentCaptor.forClass(EventFunctionItem.class);
        verify(itemRepo, times(3)).save(items.capture());
        assertThat(items.getAllValues()).extracting(EventFunctionItem::getEventFunctionId)
                .containsExactly(functions.get(0).getId(), functions.get(1).getId(), functions.get(2).getId());
        assertThat(items.getAllValues()).extracting(EventFunctionItem::getDescription).containsOnlyNulls();
        assertThat(items.getAllValues()).extracting(EventFunctionItem::getQtyBasis).containsExactly(
                EventFunctionItem.QtyBasis.PER_GUARANTEED_PAX, EventFunctionItem.QtyBasis.FIXED,
                EventFunctionItem.QtyBasis.PER_GUARANTEED_PAX);
        verify(reservationSync, times(3)).syncFunction(eq(event), any());
    }

    @Test
    void splitFixedWithDifferenceIsRejected() {
        when(packageService.quote(1L, 50L, START.toLocalDate(), 120, "EUR")).thenReturn(quote(Product.PackagePricing.SPLIT_FIXED,
                new BigDecimal("-5.00"),
                line(component(101L, EventFunction.FunctionType.PLENARY, 0, 480), "Main Hall rent", 120)));

        assertThatThrownBy(() -> service.apply(5L, new EventDtos.ApplyPackageRequest(50L, null, null, START, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ");
        verify(functionRepo, never()).save(any());
    }

    private void stubSaves() {
        AtomicLong ids = new AtomicLong(700);
        when(functionRepo.save(any(EventFunction.class))).thenAnswer(inv -> {
            EventFunction f = inv.getArgument(0);
            f.setId(ids.incrementAndGet());
            return f;
        });
    }

    private static ProductComponent component(Long productId, EventFunction.FunctionType type, int offset, int duration) {
        return ProductComponent.builder().componentProductId(productId).qty(productId == 102L ? 2 : 1)
                .qtyBasis(ProductComponent.QtyBasis.PER_PAX).included(true).functionType(type)
                .startOffsetMinutes(offset).durationMinutes(duration).build();
    }

    private static PackageService.ComponentQuote line(ProductComponent component, String name, int qty) {
        return new PackageService.ComponentQuote(component, name, qty, new BigDecimal("10.00"),
                new BigDecimal("10.00").multiply(BigDecimal.valueOf(qty)), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static PackageService.PackageQuote quote(Product.PackagePricing pricing, BigDecimal difference,
                                                     PackageService.ComponentQuote... lines) {
        return new PackageService.PackageQuote(50L, "Day Delegate Rate", pricing, 120, new BigDecimal("65.00"),
                new BigDecimal("7800.00"), new BigDecimal("7800.00"), BigDecimal.ZERO, difference, List.of(lines));
    }
}
