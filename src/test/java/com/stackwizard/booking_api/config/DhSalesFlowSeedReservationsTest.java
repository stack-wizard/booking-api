package com.stackwizard.booking_api.config;

import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.service.EventReservationSync;
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
    @Mock EventReservationSync reservationSync;

    @Test
    void holdsARoomAndSkipsAFunctionWithoutOne() {
        Event event = Event.builder().id(6L).status(Event.Status.DEFINITE).build();
        EventFunction plenary = EventFunction.builder().id(5L).tenantId(2L).resourceId(3L).name("Day Delegate Rate").build();
        EventFunction coffee = EventFunction.builder().id(7L).tenantId(2L).name("Coffee break").build();
        when(reservationRepo.findActiveByEventFunctionId(2L, 5L)).thenReturn(List.of());

        new DhSalesFlowSeedReservations(reservationRepo, reservationSync).holdMissing(event, List.of(plenary, coffee));

        verify(reservationSync).syncFunction(event, plenary);
        verify(reservationSync, never()).syncFunction(event, coffee);
    }

    @Test
    void aFailedReservationDoesNotStopTheNextFunction() {
        Event event = Event.builder().id(6L).status(Event.Status.DEFINITE).build();
        EventFunction plenary = EventFunction.builder().id(5L).tenantId(2L).resourceId(3L).name("Day Delegate Rate").build();
        EventFunction breakout = EventFunction.builder().id(6L).tenantId(2L).resourceId(5L).name("Workshop").build();
        when(reservationRepo.findActiveByEventFunctionId(2L, 5L)).thenReturn(List.of());
        when(reservationRepo.findActiveByEventFunctionId(2L, 6L)).thenReturn(List.of());
        doThrow(new IllegalStateException("hall is taken")).when(reservationSync).syncFunction(event, plenary);

        new DhSalesFlowSeedReservations(reservationRepo, reservationSync).holdMissing(event, List.of(plenary, breakout));

        verify(reservationSync).syncFunction(event, breakout);
    }

    @Test
    void leavesAFunctionThatAlreadyHasAReservation() {
        Event event = Event.builder().id(6L).status(Event.Status.DEFINITE).build();
        EventFunction plenary = EventFunction.builder().id(5L).tenantId(2L).resourceId(3L).name("Day Delegate Rate").build();
        when(reservationRepo.findActiveByEventFunctionId(2L, 5L)).thenReturn(List.of(Reservation.builder().id(1L).build()));

        new DhSalesFlowSeedReservations(reservationRepo, reservationSync).holdMissing(event, List.of(plenary));

        verify(reservationSync, never()).syncFunction(event, plenary);
    }
}
