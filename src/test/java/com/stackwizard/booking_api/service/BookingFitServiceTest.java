package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingFitServiceTest {

    @Mock BookingService bookingService;
    @Mock BookingFunctionRepository functionRepo;
    @Mock BookingRoomBlockRepository roomBlockRepo;
    @Mock ReservationRepository reservationRepo;
    @Mock ResourceRepository resourceRepo;
    @Mock PlatformTenantMappingRepository mappingRepo;
    @Mock TenantHierarchy hierarchy;
    @Mock ResourceSetupCapacityService spaceService;
    @Mock AllocationCapacityService capacityService;
    @Mock BookingReservationSync reservationSync;

    static final LocalDate ARRIVAL = LocalDate.of(2026, 11, 9);
    static final LocalDateTime FROM = LocalDateTime.of(2026, 11, 10, 8, 0);
    static final LocalDateTime TO = LocalDateTime.of(2026, 11, 10, 18, 0);

    private Resource rooms(Long id, Long hotel) {
        return Resource.builder().id(id).tenantId(hotel).kind("POOL").poolTotalUnits(40).status("ACTIVE").name("Double")
                .resourceType(ResourceType.builder().code("ROOM").build()).build();
    }

    private ResourceSetupCapacityService.SpaceCandidate space(Long id, boolean available) {
        return new ResourceSetupCapacityService.SpaceCandidate(id, "Hall " + id, "MEETING_ROOM", null, null, null,
                List.of(new ResourceSetupCapacityService.SetupCapacity("THEATRE", 200)), available);
    }

    @Test
    void reportsPerHotelWhichNeedsFitExcludingOwnLines() {
        Booking booking = Booking.builder().id(5L).tenantId(1L).status(Booking.Status.TENTATIVE).build();
        BookingFunction plenary = BookingFunction.builder().id(11L).tenantId(7L).bookingId(5L).resourceId(3L)
                .name("Plenary").functionType(BookingFunction.FunctionType.PLENARY).pax(150).setupStyle("THEATRE")
                .startsAt(FROM).endsAt(TO).occupancyStartsAt(FROM).occupancyEndsAt(TO).build();
        BookingRoomBlock block = BookingRoomBlock.builder().id(21L).tenantId(1L).bookingId(5L)
                .dateFrom(ARRIVAL).dateTo(ARRIVAL.plusDays(2)).paxPerRoom(2).build();
        Map<LocalDate, Integer> quota = new LinkedHashMap<>();
        quota.put(ARRIVAL, 20);
        quota.put(ARRIVAL.plusDays(1), 30);
        Resource roomsA = rooms(30L, 7L);
        Resource roomsB = rooms(40L, 8L);

        when(bookingService.requireBooking(5L)).thenReturn(booking);
        when(functionRepo.findForBooking(1L, 5L)).thenReturn(List.of(plenary));
        when(roomBlockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(1L, 5L)).thenReturn(List.of(block));
        when(reservationSync.activeLines(any())).thenReturn(List.of(Reservation.builder().id(99L).build()));
        when(reservationSync.quota(block)).thenReturn(quota);
        when(hierarchy.propertyIdsOf(1L)).thenReturn(List.of(7L, 8L));
        when(mappingRepo.findByTenantIdIn(any())).thenReturn(List.of(
                PlatformTenantMapping.builder().tenantId(7L).name("Hotel A").build(),
                PlatformTenantMapping.builder().tenantId(8L).name("Hotel B").build()));
        when(spaceService.searchSpaces(eq(7L), eq(150), eq("THEATRE"), eq(FROM), eq(TO), eq(Set.of(99L))))
                .thenReturn(List.of(space(3L, true)));
        when(spaceService.searchSpaces(eq(8L), eq(150), eq("THEATRE"), eq(FROM), eq(TO), eq(Set.of(99L))))
                .thenReturn(List.of(space(4L, false)));
        when(resourceRepo.findByTenantId(7L)).thenReturn(List.of(roomsA));
        when(resourceRepo.findByTenantId(8L)).thenReturn(List.of(roomsB));
        when(capacityService.freeUnits(eq(roomsA), any(), any(), any())).thenReturn(35);
        when(capacityService.freeUnits(eq(roomsB), any(), any(), any())).thenReturn(25);

        BookingDtos.FitResult result = new BookingFitService(bookingService, functionRepo, roomBlockRepo, reservationRepo,
                resourceRepo, mappingRepo, hierarchy, spaceService, capacityService, reservationSync).fit(5L);

        BookingDtos.FitHotel hotelA = result.getHotels().get(0);
        assertThat(hotelA.getName()).isEqualTo("Hotel A");
        assertThat(hotelA.isFitsAll()).isTrue();
        assertThat(hotelA.getNeeds().get(0).getCandidates()).singleElement().satisfies(c -> {
            assertThat(c.isPlaced()).isTrue();
            assertThat(c.getCapacity()).isEqualTo(200);
        });
        assertThat(hotelA.getNeeds().get(1).getRooms()).isEqualTo(30);

        BookingDtos.FitHotel hotelB = result.getHotels().get(1);
        assertThat(hotelB.isFitsAll()).isFalse();
        assertThat(hotelB.getFittingNeeds()).isZero();
        assertThat(hotelB.getNeeds().get(1).getCandidates()).singleElement().satisfies(c -> {
            assertThat(c.getFreeUnits()).isEqualTo(25);
            assertThat(c.isAvailable()).isFalse();
        });
    }
}
