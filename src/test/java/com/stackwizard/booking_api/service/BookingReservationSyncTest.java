package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.BookingRoomBlockNight;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockNightRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.opera.OperaBlockSyncService;
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
class BookingReservationSyncTest {

    @Mock ReservationService reservationService;
    @Mock ReservationRepository reservationRepo;
    @Mock AllocationRepository allocationRepo;
    @Mock BookingFunctionRepository functionRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock CrmContactRepository contactRepo;
    @Mock ReservationRequestRepository requestRepo;
    @Mock BookingRoomBlockRepository roomBlockRepo;
    @Mock BookingRoomBlockNightRepository roomNightRepo;
    @Mock OperaHotelRepository operaHotelRepo;
    @Mock OperaBlockSyncService operaBlockSync;

    BookingReservationSync sync;

    static final LocalDateTime START = LocalDateTime.of(2026, 11, 10, 7, 0);
    static final LocalDateTime END = LocalDateTime.of(2026, 11, 10, 18, 30);

    @BeforeEach
    void setUp() {
        sync = new BookingReservationSync(reservationService, reservationRepo, allocationRepo, functionRepo, accountRepo, contactRepo, requestRepo,
                roomBlockRepo, roomNightRepo, operaHotelRepo, operaBlockSync);
        when(requestRepo.findByBookingIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        when(requestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepo.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        when(reservationService.holdBookingFunctionLine(any())).thenAnswer(inv -> Reservation.builder().id(99L).build());
        when(reservationService.holdBookingRoomNight(any())).thenAnswer(inv -> Reservation.builder().id(98L).build());
        when(reservationService.resizeBookingLine(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private Booking booking(Booking.Status status) {
        return Booking.builder().id(5L).tenantId(1L).accountId(2L).name("Kongres").status(status)
                .decisionDate(LocalDate.of(2026, 10, 20)).currency("EUR").build();
    }

    private BookingFunction function(Long resourceId) {
        return BookingFunction.builder().id(11L).tenantId(1L).bookingId(5L).resourceId(resourceId)
                .functionType(BookingFunction.FunctionType.PLENARY)
                .startsAt(START.plusHours(2)).endsAt(END.minusHours(1))
                .occupancyStartsAt(START).occupancyEndsAt(END).build();
    }

    private Reservation line(Long resourceId) {
        return Reservation.builder().id(50L).bookingFunctionId(11L).status("HOLD")
                .requestedResource(Resource.builder().id(resourceId).build())
                .startsAt(START).endsAt(END).build();
    }

    @Test
    void tentativeHoldsOccupancyWindowUntilDecisionDate() {
        when(reservationRepo.findActiveByBookingFunctionId(1L, 11L)).thenReturn(List.of());

        sync.syncFunction(booking(Booking.Status.TENTATIVE), function(3L));

        ArgumentCaptor<ReservationService.BookingLineCommand> command = ArgumentCaptor.forClass(ReservationService.BookingLineCommand.class);
        verify(reservationService).holdBookingFunctionLine(command.capture());
        assertThat(command.getValue().startsAt()).isEqualTo(START);
        assertThat(command.getValue().endsAt()).isEqualTo(END);
        assertThat(command.getValue().resourceId()).isEqualTo(3L);
        assertThat(command.getValue().expiresAt()).isEqualTo(
                LocalDate.of(2026, 10, 21).atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime());
        verify(reservationService, never()).confirmBookingLines(any());
        ReservationRequest request = command.getValue().request();
        assertThat(request.getBookingId()).isEqualTo(5L);
        assertThat(request.getTenantId()).isEqualTo(1L);
        assertThat(request.getType()).isEqualTo(ReservationRequest.Type.INTERNAL);
        assertThat(request.getStatus()).isEqualTo(ReservationRequest.Status.DRAFT);
        assertThat(request.getExpiresAt()).isNull();
    }

    @Test
    void requestStatusFollowsBookingPhase() {
        ReservationRequest request = ReservationRequest.builder().id(7L).tenantId(1L).bookingId(5L)
                .status(ReservationRequest.Status.DRAFT).build();
        when(requestRepo.findByBookingIdOrderByTenantIdAsc(5L)).thenReturn(List.of(request));

        sync.syncRequests(booking(Booking.Status.DEFINITE));
        assertThat(request.getStatus()).isEqualTo(ReservationRequest.Status.FINALIZED);
        assertThat(request.getConfirmationCode()).isNotNull();

        sync.syncRequests(booking(Booking.Status.LOST));
        assertThat(request.getStatus()).isEqualTo(ReservationRequest.Status.CANCELLED);
    }

    @Test
    void emptyBookingRequestIsDropped() {
        ReservationRequest request = ReservationRequest.builder().id(7L).tenantId(1L).bookingId(5L).build();
        when(reservationRepo.existsByRequestId(7L)).thenReturn(false);
        when(requestRepo.findById(7L)).thenReturn(Optional.of(request));

        sync.dropIfEmpty(7L);

        verify(requestRepo).delete(request);
    }

    @Test
    void definiteConfirmsExistingLine() {
        Reservation existing = line(3L);
        when(reservationRepo.findActiveByBookingFunctionId(1L, 11L)).thenReturn(List.of(existing));

        sync.syncFunction(booking(Booking.Status.DEFINITE), function(3L));

        verify(reservationService).confirmBookingLines(List.of(existing));
        verify(reservationService, never()).holdBookingFunctionLine(any());
    }

    @Test
    void movingToAnotherSpaceReleasesOldLineAndHoldsNew() {
        Reservation existing = line(3L);
        when(reservationRepo.findActiveByBookingFunctionId(1L, 11L)).thenReturn(List.of(existing));

        sync.syncFunction(booking(Booking.Status.TENTATIVE), function(4L));

        verify(reservationService).releaseBookingLines(List.of(existing));
        verify(reservationService).holdBookingFunctionLine(any());
    }

    @Test
    void lostReleasesLines() {
        Reservation existing = line(3L);
        when(reservationRepo.findActiveByBookingFunctionId(1L, 11L)).thenReturn(List.of(existing));

        sync.syncFunction(booking(Booking.Status.LOST), function(3L));

        verify(reservationService).releaseBookingLines(List.of(existing));
        verify(reservationService, never()).holdBookingFunctionLine(any());
    }

    @Test
    void functionWithoutSpaceHoldsNothing() {
        when(reservationRepo.findActiveByBookingFunctionId(1L, 11L)).thenReturn(List.of());

        sync.syncFunction(booking(Booking.Status.TENTATIVE), function(null));

        verify(reservationService).releaseBookingLines(List.of());
        verify(reservationService, never()).holdBookingFunctionLine(any());
    }

    @Test
    void holdExpiryIsEndOfDecisionDay() {
        OffsetDateTime expiry = BookingReservationSync.holdExpiry(booking(Booking.Status.TENTATIVE));
        assertThat(expiry.toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(expiry.toLocalTime().getHour()).isZero();
    }

    static final LocalDate ARRIVAL = LocalDate.of(2026, 11, 9);

    private BookingRoomBlock roomBlock() {
        return BookingRoomBlock.builder().id(21L).tenantId(1L).bookingId(5L).propertyTenantId(7L).roomResourceId(30L)
                .dateFrom(ARRIVAL).dateTo(ARRIVAL.plusDays(3)).paxPerRoom(2).build();
    }

    private void quota(int... perNight) {
        List<BookingRoomBlockNight> rows = new java.util.ArrayList<>();
        for (int i = 0; i < perNight.length; i++) {
            rows.add(BookingRoomBlockNight.builder().tenantId(1L).blockId(21L).night(ARRIVAL.plusDays(i)).qty(perNight[i]).build());
        }
        when(roomNightRepo.findByTenantIdAndBlockIdOrderByNightAsc(1L, 21L)).thenReturn(rows);
    }

    private Reservation roomLine(LocalDate night, int units) {
        return Reservation.builder().id(60L + night.getDayOfMonth()).bookingRoomBlockId(21L).status("HOLD").units(units)
                .requestedResource(Resource.builder().id(30L).build())
                .startsAt(night.atTime(15, 0)).endsAt(night.plusDays(1).atTime(11, 0)).build();
    }

    @Test
    void placedRoomBlockHoldsOneLinePerNightWithQuotaAsUnits() {
        quota(5, 3, 0);
        when(reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(7L, 21L)).thenReturn(List.of());

        sync.syncRoomBlock(booking(Booking.Status.TENTATIVE), roomBlock());

        ArgumentCaptor<ReservationService.RoomNightCommand> nights = ArgumentCaptor.forClass(ReservationService.RoomNightCommand.class);
        verify(reservationService, org.mockito.Mockito.times(2)).holdBookingRoomNight(nights.capture());
        assertThat(nights.getAllValues()).extracting(ReservationService.RoomNightCommand::units).containsExactly(5, 3);
        ReservationService.RoomNightCommand first = nights.getAllValues().get(0);
        assertThat(first.startsAt()).isEqualTo(ARRIVAL.atTime(15, 0));
        assertThat(first.endsAt()).isEqualTo(ARRIVAL.plusDays(1).atTime(11, 0));
        assertThat(first.tenantId()).isEqualTo(7L);
        assertThat(first.request().getTenantId()).isEqualTo(7L);
        assertThat(first.localInventory()).isTrue();
    }

    @Test
    void quotaChangeResizesTheSameLineAndZeroReleasesIt() {
        quota(7, 0, 0);
        Reservation night1 = roomLine(ARRIVAL, 5);
        Reservation night2 = roomLine(ARRIVAL.plusDays(1), 3);
        when(reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(7L, 21L)).thenReturn(List.of(night1, night2));

        sync.syncRoomBlock(booking(Booking.Status.TENTATIVE), roomBlock());

        verify(reservationService).resizeBookingLine(night1, 7);
        verify(reservationService).releaseBookingLines(List.of(night2));
        verify(reservationService, never()).holdBookingRoomNight(any());
    }

    @Test
    void definiteConfirmsRoomLines() {
        quota(2, 2, 2);
        List<Reservation> lines = List.of(roomLine(ARRIVAL, 2), roomLine(ARRIVAL.plusDays(1), 2), roomLine(ARRIVAL.plusDays(2), 2));
        when(reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(7L, 21L)).thenReturn(lines);

        sync.syncRoomBlock(booking(Booking.Status.DEFINITE), roomBlock());

        verify(reservationService).confirmBookingLines(lines);
        verify(reservationService, never()).resizeBookingLine(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void operaInventoryHotelGetsLinesWithoutAllocationAndPendingRequest() {
        quota(4, 0, 0);
        when(reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(7L, 21L)).thenReturn(List.of());
        when(operaHotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(7L)).thenReturn(List.of(
                OperaHotel.builder().tenantId(7L).active(true)
                        .spaceInventory(OperaHotel.Mode.LOCAL)
                        .roomInventory(OperaHotel.Mode.OPERA)
                        .build()));

        sync.syncRoomBlock(booking(Booking.Status.TENTATIVE), roomBlock());

        ArgumentCaptor<ReservationService.RoomNightCommand> night = ArgumentCaptor.forClass(ReservationService.RoomNightCommand.class);
        verify(reservationService).holdBookingRoomNight(night.capture());
        assertThat(night.getValue().localInventory()).isFalse();
        assertThat(night.getValue().request().getOperaSyncStatus()).isEqualTo(ReservationRequest.OperaSyncStatus.PENDING);
    }
}
