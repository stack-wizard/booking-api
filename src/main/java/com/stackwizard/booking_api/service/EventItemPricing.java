package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
public class EventItemPricing {
    private final EventFunctionRepository functionRepo;
    private final EventFunctionItemRepository itemRepo;
    private final PriceListEntryResolver priceResolver;

    public EventItemPricing(EventFunctionRepository functionRepo,
                            EventFunctionItemRepository itemRepo,
                            PriceListEntryResolver priceResolver) {
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.priceResolver = priceResolver;
    }

    /**
     * Pax used for billing: expected while selling, guaranteed once given, max(guaranteed, actual) after the event.
     */
    public static Integer billablePax(Event event) {
        if (event.getStatus() == Event.Status.ACTUAL && event.getActualPax() != null) {
            int guaranteed = event.getGuaranteedPax() != null ? event.getGuaranteedPax() : 0;
            return Math.max(guaranteed, event.getActualPax());
        }
        if (event.getGuaranteedPax() != null) {
            return event.getGuaranteedPax();
        }
        return event.getExpectedPax();
    }

    public Optional<BigDecimal> catalogPrice(Product product, String uom, String currency, Long tenantId, LocalDate date) {
        List<PriceListEntry> entries = priceResolver.findEffectiveForProductUomOnDate(
                product.getId(), uom, currency, tenantId, date, ReservationRequest.Type.INTERNAL);
        return entries.stream().map(PriceListEntry::getPrice).filter(p -> p != null).findFirst();
    }

    public static void applyAmounts(EventFunctionItem item) {
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
    public void recalculatePerPax(Event event) {
        Integer pax = billablePax(event);
        if (pax == null || pax <= 0) {
            return;
        }
        List<Long> functionIds = functionRepo.findByTenantIdAndEventIdOrderByStartsAtAscDisplayOrderAscIdAsc(
                event.getTenantId(), event.getId()).stream().map(EventFunction::getId).toList();
        if (functionIds.isEmpty()) {
            return;
        }
        List<EventFunctionItem> items = itemRepo.findByTenantIdAndEventFunctionIdIn(event.getTenantId(), functionIds).stream()
                .filter(i -> i.getQtyBasis() == EventFunctionItem.QtyBasis.PER_GUARANTEED_PAX)
                .filter(i -> !pax.equals(i.getQty()))
                .toList();
        for (EventFunctionItem item : items) {
            item.setQty(pax);
            applyAmounts(item);
        }
        itemRepo.saveAll(items);
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
