package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventReservationSyncTest {

    @Mock ReservationService reservationService;
    @Mock ReservationRepository reservationRepo;
    @Mock AllocationRepository allocationRepo;
    @Mock EventFunctionRepository functionRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock CrmContactRepository contactRepo;

    EventReservationSync sync;

    static final LocalDateTime START = LocalDateTime.of(2026, 11, 10, 7, 0);
    static final LocalDateTime END = LocalDateTime.of(2026, 11, 10, 18, 30);

    @BeforeEach
    void setUp() {
        sync = new EventReservationSync(reservationService, reservationRepo, allocationRepo, functionRepo, accountRepo, contactRepo);
        when(accountRepo.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        when(reservationService.holdEventFunctionLine(any())).thenAnswer(inv -> Reservation.builder().id(99L).build());
    }

    private Event event(Event.Status status) {
        return Event.builder().id(5L).tenantId(1L).accountId(2L).name("Kongres").status(status)
                .decisionDate(LocalDate.of(2026, 10, 20)).currency("EUR").build();
    }

    private EventFunction function(Long resourceId) {
        return EventFunction.builder().id(11L).tenantId(1L).eventId(5L).resourceId(resourceId)
                .functionType(EventFunction.FunctionType.PLENARY)
                .startsAt(START.plusHours(2)).endsAt(END.minusHours(1))
                .occupancyStartsAt(START).occupancyEndsAt(END).build();
    }

    private Reservation line(Long resourceId) {
        return Reservation.builder().id(50L).eventFunctionId(11L).status("HOLD")
                .requestedResource(Resource.builder().id(resourceId).build())
                .startsAt(START).endsAt(END).build();
    }

    @Test
    void tentativeHoldsOccupancyWindowUntilDecisionDate() {
        when(reservationRepo.findActiveByEventFunctionId(1L, 11L)).thenReturn(List.of());

        sync.syncFunction(event(Event.Status.TENTATIVE), function(3L));

        ArgumentCaptor<ReservationService.EventLineCommand> command = ArgumentCaptor.forClass(ReservationService.EventLineCommand.class);
        verify(reservationService).holdEventFunctionLine(command.capture());
        assertThat(command.getValue().startsAt()).isEqualTo(START);
        assertThat(command.getValue().endsAt()).isEqualTo(END);
        assertThat(command.getValue().resourceId()).isEqualTo(3L);
        assertThat(command.getValue().expiresAt()).isEqualTo(
                LocalDate.of(2026, 10, 21).atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime());
        verify(reservationService, never()).confirmEventLines(any());
    }

    @Test
    void definiteConfirmsExistingLine() {
        Reservation existing = line(3L);
        when(reservationRepo.findActiveByEventFunctionId(1L, 11L)).thenReturn(List.of(existing));

        sync.syncFunction(event(Event.Status.DEFINITE), function(3L));

        verify(reservationService).confirmEventLines(List.of(existing));
        verify(reservationService, never()).holdEventFunctionLine(any());
    }

    @Test
    void movingToAnotherSpaceReleasesOldLineAndHoldsNew() {
        Reservation existing = line(3L);
        when(reservationRepo.findActiveByEventFunctionId(1L, 11L)).thenReturn(List.of(existing));

        sync.syncFunction(event(Event.Status.TENTATIVE), function(4L));

        verify(reservationService).releaseEventLines(List.of(existing));
        verify(reservationService).holdEventFunctionLine(any());
    }

    @Test
    void lostReleasesLines() {
        Reservation existing = line(3L);
        when(reservationRepo.findActiveByEventFunctionId(1L, 11L)).thenReturn(List.of(existing));

        sync.syncFunction(event(Event.Status.LOST), function(3L));

        verify(reservationService).releaseEventLines(List.of(existing));
        verify(reservationService, never()).holdEventFunctionLine(any());
    }

    @Test
    void functionWithoutSpaceHoldsNothing() {
        when(reservationRepo.findActiveByEventFunctionId(1L, 11L)).thenReturn(List.of());

        sync.syncFunction(event(Event.Status.TENTATIVE), function(null));

        verify(reservationService).releaseEventLines(List.of());
        verify(reservationService, never()).holdEventFunctionLine(any());
    }

    @Test
    void holdExpiryIsEndOfDecisionDay() {
        OffsetDateTime expiry = EventReservationSync.holdExpiry(event(Event.Status.TENTATIVE));
        assertThat(expiry.toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(expiry.toLocalTime().getHour()).isZero();
    }
}
