package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.BookingFunctionItemRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.service.opera.OperaRoomAvailabilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingReadModelServiceTest {

    @Mock BookingService bookingService;
    @Mock BookingRepository bookingRepo;
    @Mock BookingFunctionRepository functionRepo;
    @Mock BookingFunctionItemRepository itemRepo;
    @Mock BookingReservationSync reservationSync;
    @Mock ResourceRepository resourceRepo;
    @Mock AllocationRepository allocationRepo;
    @Mock BookingRoomBlockRepository roomBlockRepo;
    @Mock ReservationRepository reservationRepo;
    @Mock ReservationRequestRepository requestRepo;
    @Mock PlatformTenantMappingRepository mappingRepo;
    @Mock TenantHierarchy hierarchy;
    @Mock OperaHotelRepository operaHotelRepo;
    @Mock OperaRoomAvailabilityService operaAvailability;

    private BookingReadModelService service() {
        return new BookingReadModelService(
                bookingService, bookingRepo, functionRepo, itemRepo, reservationSync,
                resourceRepo, allocationRepo, null, roomBlockRepo, reservationRepo, requestRepo, mappingRepo, hierarchy,
                operaHotelRepo, operaAvailability);
    }

    @Test
    void financialsAddRoomsAndSplitPerHotel() {
        Booking booking = Booking.builder().id(6L).tenantId(1L).currency("EUR")
                .status(Booking.Status.TENTATIVE).build();
        BookingFunction function = BookingFunction.builder()
                .id(5L).tenantId(7L).bookingId(6L).name("Plenary")
                .functionType(BookingFunction.FunctionType.PLENARY).build();
        BookingFunctionItem item = BookingFunctionItem.builder()
                .bookingFunctionId(5L).grossAmount(new BigDecimal("300.00")).build();
        BookingRoomBlock block = BookingRoomBlock.builder().id(21L).tenantId(1L).bookingId(6L)
                .propertyTenantId(8L).roomResourceId(30L).build();
        when(bookingService.requireBooking(6L)).thenReturn(booking);
        when(functionRepo.findForBooking(1L, 6L)).thenReturn(List.of(function));
        when(reservationSync.activeLines(any())).thenReturn(List.of());
        when(itemRepo.findForFunctions(eq(1L), eq(List.of(5L)))).thenReturn(List.of(item));
        when(roomBlockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(1L, 6L)).thenReturn(List.of(block));
        when(reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(8L, 21L)).thenReturn(List.of(
                Reservation.builder().id(1L).status("HOLD").grossAmount(new BigDecimal("400.00")).build(),
                Reservation.builder().id(2L).status("HOLD").grossAmount(new BigDecimal("400.00")).build(),
                Reservation.builder().id(3L).status("CANCELLED").grossAmount(new BigDecimal("999.00")).build()));
        when(requestRepo.findByBookingIdOrderByTenantIdAsc(6L)).thenReturn(List.of(
                ReservationRequest.builder().id(70L).tenantId(8L).status(ReservationRequest.Status.DRAFT).build()));

        BookingDtos.Financials financials = service().financials(6L);

        assertThat(financials.getRoomsTotal()).isEqualByComparingTo("800.00");
        assertThat(financials.getTotal()).isEqualByComparingTo("1100.00");
        assertThat(financials.getHotels()).hasSize(2);
        assertThat(financials.getHotels().get(0).getTenantId()).isEqualTo(7L);
        assertThat(financials.getHotels().get(0).getTotal()).isEqualByComparingTo("300.00");
        assertThat(financials.getHotels().get(1).getRoomsAmount()).isEqualByComparingTo("800.00");
        assertThat(financials.getHotels().get(1).getReservationRequestId()).isEqualTo(70L);
    }

    @Test
    void summaryDerivesKindAndHotels() {
        Booking booking = Booking.builder().id(6L).tenantId(1L).build();
        BookingFunction function = BookingFunction.builder().id(5L).tenantId(7L).bookingId(6L)
                .functionType(BookingFunction.FunctionType.PLENARY).build();
        BookingRoomBlock block = BookingRoomBlock.builder().id(21L).tenantId(1L).bookingId(6L).propertyTenantId(8L).build();
        BookingRoomBlock unplaced = BookingRoomBlock.builder().id(22L).tenantId(1L).bookingId(6L).build();
        when(bookingService.forOpportunity(3L)).thenReturn(List.of(booking));
        when(functionRepo.findForBooking(1L, 6L)).thenReturn(List.of(function));
        when(itemRepo.findForFunctions(eq(1L), eq(List.of(5L)))).thenReturn(List.of());
        when(roomBlockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(1L, 6L)).thenReturn(List.of(block, unplaced));

        BookingDtos.BookingSummary summary = service().opportunitySummaries(3L).get(0);

        assertThat(summary.getKind()).isEqualTo("CONFERENCE");
        assertThat(summary.getRoomBlockCount()).isEqualTo(2);
        assertThat(summary.getHotels()).extracting(BookingDtos.SummaryHotel::getTenantId).containsExactly(7L, 8L);
    }

    @Test
    void kindFollowsNeeds() {
        assertThat(BookingReadModelService.kindOf(0, 0, false)).isEqualTo("EMPTY");
        assertThat(BookingReadModelService.kindOf(0, 1, false)).isEqualTo("GROUP");
        assertThat(BookingReadModelService.kindOf(1, 0, false)).isEqualTo("SPACE_RENTAL");
        assertThat(BookingReadModelService.kindOf(1, 0, true)).isEqualTo("EVENT");
        assertThat(BookingReadModelService.kindOf(3, 0, false)).isEqualTo("EVENT");
    }

    @Test
    void financialsWhenNoFunctionHasARoom() {
        Booking booking = Booking.builder().id(6L).tenantId(1L).currency("EUR")
                .status(Booking.Status.DEFINITE).guaranteedPax(120).build();
        BookingFunction function = BookingFunction.builder()
                .id(5L).tenantId(1L).bookingId(6L).name("Day Delegate Rate")
                .functionType(BookingFunction.FunctionType.PLENARY).build();
        BookingFunctionItem item = BookingFunctionItem.builder()
                .bookingFunctionId(5L).grossAmount(new BigDecimal("7800.00")).build();
        when(bookingService.requireBooking(6L)).thenReturn(booking);
        when(functionRepo.findForBooking(1L, 6L)).thenReturn(List.of(function));
        when(reservationSync.activeLines(any())).thenReturn(List.of());
        when(itemRepo.findForFunctions(eq(1L), eq(List.of(5L)))).thenReturn(List.of(item));

        BookingReadModelService service = service();

        BookingDtos.Financials financials = service.financials(6L);

        assertThat(financials.getItemsTotal()).isEqualByComparingTo("7800.00");
        assertThat(financials.getTotal()).isEqualByComparingTo("7800.00");
        assertThat(financials.getFunctions()).singleElement().satisfies(row -> {
            assertThat(row.getResourceId()).isNull();
            assertThat(row.getResourceName()).isNull();
        });
    }
}
