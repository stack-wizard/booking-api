package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.repository.BookingFunctionItemRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
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
class BookingPackageServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2027, 4, 14, 9, 0);

    @Mock BookingService bookingService;
    @Mock BookingFunctionService functionService;
    @Mock PackageService packageService;
    @Mock BookingFunctionRepository functionRepo;
    @Mock BookingFunctionItemRepository itemRepo;
    @Mock ProductRepository productRepo;
    @Mock BookingReservationSync reservationSync;

    BookingPackageService service;
    Booking booking;

    @BeforeEach
    void setUp() {
        service = new BookingPackageService(bookingService, functionService, packageService, functionRepo, itemRepo,
                productRepo, reservationSync);
        booking = Booking.builder().id(5L).tenantId(1L).status(Booking.Status.INQUIRY).currency("EUR")
                .dateFrom(LocalDate.of(2027, 4, 14)).dateTo(LocalDate.of(2027, 4, 15)).guaranteedPax(120).build();
        when(bookingService.requireContentEditable(5L)).thenReturn(booking);
        when(functionService.resolveProperty(any(), any())).thenReturn(1L);
    }

    @Test
    void componentItemsLandOnTheFunctionsTheyGenerate() {
        stubSaves();
        when(productRepo.findByIdAndTenantId(anyLong(), eq(1L)))
                .thenAnswer(inv -> Optional.of(Product.builder().id(inv.getArgument(0)).name("P" + inv.getArgument(0))
                        .defaultUom("UNIT").build()));
        when(packageService.quote(1L, 1L, 50L, START.toLocalDate(), 120, "EUR")).thenReturn(quote(Product.PackagePricing.SPLIT_PERCENT,
                BigDecimal.ZERO,
                line(component(101L, BookingFunction.FunctionType.PLENARY, 0, 480), "Main Hall rent", 120),
                line(component(102L, BookingFunction.FunctionType.COFFEE_BREAK, 90, 30), "Coffee break", 240),
                line(component(103L, BookingFunction.FunctionType.LUNCH, 210, 60), "Business lunch", 120)));

        List<BookingFunction> functions = service.apply(5L, new BookingDtos.ApplyPackageRequest(50L, 9L, null, START, 120));

        assertThat(functions).extracting(BookingFunction::getName)
                .containsExactly("Day Delegate Rate", "Coffee break", "Business lunch");
        assertThat(functions).extracting(BookingFunction::getResourceId).containsExactly(9L, null, null);
        assertThat(functions.get(1).getStartsAt()).isEqualTo(START.plusMinutes(90));

        ArgumentCaptor<BookingFunctionItem> items = ArgumentCaptor.forClass(BookingFunctionItem.class);
        verify(itemRepo, times(3)).save(items.capture());
        assertThat(items.getAllValues()).extracting(BookingFunctionItem::getBookingFunctionId)
                .containsExactly(functions.get(0).getId(), functions.get(1).getId(), functions.get(2).getId());
        assertThat(items.getAllValues()).extracting(BookingFunctionItem::getDescription).containsOnlyNulls();
        assertThat(items.getAllValues()).extracting(BookingFunctionItem::getQtyBasis).containsExactly(
                BookingFunctionItem.QtyBasis.PER_GUARANTEED_PAX, BookingFunctionItem.QtyBasis.FIXED,
                BookingFunctionItem.QtyBasis.PER_GUARANTEED_PAX);
        verify(reservationSync, times(3)).syncFunction(eq(booking), any());
    }

    @Test
    void splitFixedWithDifferenceIsRejected() {
        when(packageService.quote(1L, 1L, 50L, START.toLocalDate(), 120, "EUR")).thenReturn(quote(Product.PackagePricing.SPLIT_FIXED,
                new BigDecimal("-5.00"),
                line(component(101L, BookingFunction.FunctionType.PLENARY, 0, 480), "Main Hall rent", 120)));

        assertThatThrownBy(() -> service.apply(5L, new BookingDtos.ApplyPackageRequest(50L, null, null, START, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("differ");
        verify(functionRepo, never()).save(any());
    }

    private void stubSaves() {
        AtomicLong ids = new AtomicLong(700);
        when(functionRepo.save(any(BookingFunction.class))).thenAnswer(inv -> {
            BookingFunction f = inv.getArgument(0);
            f.setId(ids.incrementAndGet());
            return f;
        });
    }

    private static ProductComponent component(Long productId, BookingFunction.FunctionType type, int offset, int duration) {
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
