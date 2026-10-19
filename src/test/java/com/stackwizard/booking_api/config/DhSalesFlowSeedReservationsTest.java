package com.stackwizard.booking_api.config;

import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.service.BookingReservationSync;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DhSalesFlowSeedReservationsTest {

    @Mock ReservationRepository reservationRepo;
    @Mock BookingReservationSync reservationSync;

    @Test
    void holdsARoomAndSkipsAFunctionWithoutOne() {
        Booking booking = Booking.builder().id(6L).status(Booking.Status.DEFINITE).build();
        BookingFunction plenary = BookingFunction.builder().id(5L).tenantId(2L).resourceId(3L).name("Day Delegate Rate").build();
        BookingFunction coffee = BookingFunction.builder().id(7L).tenantId(2L).name("Coffee break").build();
        when(reservationRepo.findActiveByBookingFunctionId(2L, 5L)).thenReturn(List.of());

        new DhSalesFlowSeedReservations(reservationRepo, reservationSync).holdMissing(booking, List.of(plenary, coffee));

        verify(reservationSync).syncFunction(booking, plenary);
        verify(reservationSync, never()).syncFunction(booking, coffee);
    }

    @Test
    void aFailedReservationDoesNotStopTheNextFunction() {
        Booking booking = Booking.builder().id(6L).status(Booking.Status.DEFINITE).build();
        BookingFunction plenary = BookingFunction.builder().id(5L).tenantId(2L).resourceId(3L).name("Day Delegate Rate").build();
        BookingFunction breakout = BookingFunction.builder().id(6L).tenantId(2L).resourceId(5L).name("Workshop").build();
        when(reservationRepo.findActiveByBookingFunctionId(2L, 5L)).thenReturn(List.of());
        when(reservationRepo.findActiveByBookingFunctionId(2L, 6L)).thenReturn(List.of());
        doThrow(new IllegalStateException("hall is taken")).when(reservationSync).syncFunction(booking, plenary);

        new DhSalesFlowSeedReservations(reservationRepo, reservationSync).holdMissing(booking, List.of(plenary, breakout));

        verify(reservationSync).syncFunction(booking, breakout);
    }

    @Test
    void leavesAFunctionThatAlreadyHasAReservation() {
        Booking booking = Booking.builder().id(6L).status(Booking.Status.DEFINITE).build();
        BookingFunction plenary = BookingFunction.builder().id(5L).tenantId(2L).resourceId(3L).name("Day Delegate Rate").build();
        when(reservationRepo.findActiveByBookingFunctionId(2L, 5L)).thenReturn(List.of(Reservation.builder().id(1L).build()));

        new DhSalesFlowSeedReservations(reservationRepo, reservationSync).holdMissing(booking, List.of(plenary));

        verify(reservationSync, never()).syncFunction(booking, plenary);
    }
}
