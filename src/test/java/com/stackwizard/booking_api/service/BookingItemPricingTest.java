package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingItemPricingTest {

    @Test
    void billablePaxFollowsExpectedGuaranteedActual() {
        Booking booking = Booking.builder().status(Booking.Status.TENTATIVE).expectedPax(100).build();
        assertThat(BookingItemPricing.billablePax(booking)).isEqualTo(100);

        booking.setGuaranteedPax(90);
        assertThat(BookingItemPricing.billablePax(booking)).isEqualTo(90);

        booking.setStatus(Booking.Status.ACTUAL);
        booking.setActualPax(85);
        assertThat(BookingItemPricing.billablePax(booking)).isEqualTo(90);

        booking.setActualPax(96);
        assertThat(BookingItemPricing.billablePax(booking)).isEqualTo(96);
    }

    @Test
    void amountsSubtractDiscount() {
        BookingFunctionItem item = BookingFunctionItem.builder().qty(3).unitPrice(new BigDecimal("12.5"))
                .discountAmount(new BigDecimal("2.5")).build();

        BookingItemPricing.applyAmounts(item);

        assertThat(item.getGrossAmount()).isEqualByComparingTo("35.00");
    }

    @Test
    void discountCannotExceedLine() {
        BookingFunctionItem item = BookingFunctionItem.builder().qty(1).unitPrice(BigDecimal.TEN)
                .discountAmount(new BigDecimal("11")).build();

        assertThatThrownBy(() -> BookingItemPricing.applyAmounts(item)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bookingLineQtyPerUom() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 10, 7, 0);
        assertThat(ReservationService.deriveBookingLineQty("HOUR", start, start.plusMinutes(690))).isEqualTo(12);
        assertThat(ReservationService.deriveBookingLineQty("DAY", start, start.plusHours(11))).isEqualTo(1);
        assertThat(ReservationService.deriveBookingLineQty("DAY", start, start.plusDays(2))).isEqualTo(3);
        assertThat(ReservationService.deriveBookingLineQty("DAY", start.withHour(0), start.withHour(0).plusDays(1))).isEqualTo(1);
        assertThat(ReservationService.deriveBookingLineQty("MINUTE", start, start.plusMinutes(45))).isEqualTo(45);
    }
}
