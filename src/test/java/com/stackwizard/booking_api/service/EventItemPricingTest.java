package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunctionItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventItemPricingTest {

    @Test
    void billablePaxFollowsExpectedGuaranteedActual() {
        Event event = Event.builder().status(Event.Status.TENTATIVE).expectedPax(100).build();
        assertThat(EventItemPricing.billablePax(event)).isEqualTo(100);

        event.setGuaranteedPax(90);
        assertThat(EventItemPricing.billablePax(event)).isEqualTo(90);

        event.setStatus(Event.Status.ACTUAL);
        event.setActualPax(85);
        assertThat(EventItemPricing.billablePax(event)).isEqualTo(90);

        event.setActualPax(96);
        assertThat(EventItemPricing.billablePax(event)).isEqualTo(96);
    }

    @Test
    void amountsSubtractDiscount() {
        EventFunctionItem item = EventFunctionItem.builder().qty(3).unitPrice(new BigDecimal("12.5"))
                .discountAmount(new BigDecimal("2.5")).build();

        EventItemPricing.applyAmounts(item);

        assertThat(item.getGrossAmount()).isEqualByComparingTo("35.00");
    }

    @Test
    void discountCannotExceedLine() {
        EventFunctionItem item = EventFunctionItem.builder().qty(1).unitPrice(BigDecimal.TEN)
                .discountAmount(new BigDecimal("11")).build();

        assertThatThrownBy(() -> EventItemPricing.applyAmounts(item)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventLineQtyPerUom() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 10, 7, 0);
        assertThat(ReservationService.deriveEventLineQty("HOUR", start, start.plusMinutes(690))).isEqualTo(12);
        assertThat(ReservationService.deriveEventLineQty("DAY", start, start.plusHours(11))).isEqualTo(1);
        assertThat(ReservationService.deriveEventLineQty("DAY", start, start.plusDays(2))).isEqualTo(3);
        assertThat(ReservationService.deriveEventLineQty("DAY", start.withHour(0), start.withHour(0).plusDays(1))).isEqualTo(1);
        assertThat(ReservationService.deriveEventLineQty("MINUTE", start, start.plusMinutes(45))).isEqualTo(45);
    }
}
