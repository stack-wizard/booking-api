package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.BookingFunctionItemRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
public class BookingItemPricing {
    private final BookingFunctionRepository functionRepo;
    private final BookingFunctionItemRepository itemRepo;
    private final PriceListEntryResolver priceResolver;

    public BookingItemPricing(BookingFunctionRepository functionRepo,
                            BookingFunctionItemRepository itemRepo,
                            PriceListEntryResolver priceResolver) {
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.priceResolver = priceResolver;
    }

    /**
     * Pax used for billing: expected while selling, guaranteed once given, max(guaranteed, actual) after the booking.
     */
    public static Integer billablePax(Booking booking) {
        if (booking.getStatus() == Booking.Status.ACTUAL && booking.getActualPax() != null) {
            int guaranteed = booking.getGuaranteedPax() != null ? booking.getGuaranteedPax() : 0;
            return Math.max(guaranteed, booking.getActualPax());
        }
        if (booking.getGuaranteedPax() != null) {
            return booking.getGuaranteedPax();
        }
        return booking.getExpectedPax();
    }

    public Optional<BigDecimal> catalogPrice(Product product, String uom, String currency, Long tenantId, LocalDate date) {
        List<PriceListEntry> entries = priceResolver.findEffectiveForProductUomOnDate(
                product.getId(), uom, currency, tenantId, date, ReservationRequest.Type.INTERNAL);
        return entries.stream().map(PriceListEntry::getPrice).filter(p -> p != null).findFirst();
    }

    public static void applyAmounts(BookingFunctionItem item) {
        BigDecimal unit = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal discount = item.getDiscountAmount() != null ? item.getDiscountAmount() : BigDecimal.ZERO;
        BigDecimal gross = unit.multiply(BigDecimal.valueOf(item.getQty()));
        if (discount.signum() < 0 || discount.compareTo(gross) > 0) {
            throw new IllegalArgumentException("discountAmount must be between 0 and the line amount");
        }
        item.setUnitPrice(money(unit));
        item.setDiscountAmount(money(discount));
        item.setGrossAmount(money(gross.subtract(discount)));
    }

    @Transactional
    public void recalculatePerPax(Booking booking) {
        Integer pax = billablePax(booking);
        if (pax == null || pax <= 0) {
            return;
        }
        List<Long> functionIds = functionRepo.findForBooking(
                booking.getTenantId(), booking.getId()).stream().map(BookingFunction::getId).toList();
        if (functionIds.isEmpty()) {
            return;
        }
        List<BookingFunctionItem> items = itemRepo.findForFunctions(booking.getTenantId(), functionIds).stream()
                .filter(i -> i.getQtyBasis() == BookingFunctionItem.QtyBasis.PER_GUARANTEED_PAX)
                .filter(i -> !pax.equals(i.getQty()))
                .toList();
        for (BookingFunctionItem item : items) {
            item.setQty(pax);
            applyAmounts(item);
        }
        itemRepo.saveAll(items);
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
