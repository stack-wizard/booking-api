package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.BookingRoomBlockNight;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.repository.BookingRoomBlockNightRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.service.opera.OperaRoomAvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingRoomBlockServiceTest {

    @Mock BookingService bookingService;
    @Mock BookingRoomBlockRepository blockRepo;
    @Mock BookingRoomBlockNightRepository nightRepo;
    @Mock ResourceRepository resourceRepo;
    @Mock ReservationRepository reservationRepo;
    @Mock OperaHotelRepository operaHotelRepo;
    @Mock TenantHierarchy hierarchy;
    @Mock BookingReservationSync reservationSync;
    @Mock AllocationCapacityService capacityService;
    @Mock OperaRoomAvailabilityService operaAvailability;

    BookingRoomBlockService service;
    Booking booking;

    static final LocalDate ARRIVAL = LocalDate.of(2026, 11, 9);

    @BeforeEach
    void setUp() {
        service = new BookingRoomBlockService(bookingService, blockRepo, nightRepo, resourceRepo, reservationRepo,
                operaHotelRepo, hierarchy, reservationSync, capacityService, operaAvailability);
        booking = Booking.builder().id(5L).tenantId(1L).status(Booking.Status.TENTATIVE).build();
        when(bookingService.requireContentEditable(5L)).thenReturn(booking);
        when(bookingService.requireBooking(5L)).thenReturn(booking);
        when(blockRepo.save(any())).thenAnswer(inv -> {
            BookingRoomBlock block = inv.getArgument(0);
            if (block.getId() == null) {
                block.setId(21L);
            }
            return block;
        });
        when(reservationSync.quota(any())).thenReturn(Map.of());
        when(hierarchy.isPropertyOf(7L, 1L)).thenReturn(true);
        when(hierarchy.isPropertyOf(8L, 1L)).thenReturn(true);
        when(hierarchy.requirePropertyOf(7L, 1L)).thenReturn(7L);
        when(hierarchy.requirePropertyOf(8L, 1L)).thenReturn(8L);
    }

    private Resource pool(Long id, Long tenantId, String type) {
        return Resource.builder().id(id).tenantId(tenantId).kind("POOL").poolTotalUnits(40).name("Double")
                .resourceType(ResourceType.builder().code(type).build()).build();
    }

    private BookingDtos.RoomBlockRequest request(Long propertyTenantId, Integer rooms, List<BookingDtos.RoomNight> nights) {
        BookingDtos.RoomBlockRequest request = new BookingDtos.RoomBlockRequest();
        request.setPropertyTenantId(propertyTenantId);
        request.setRoomTypeLabel("Double for delegates");
        request.setDateFrom(ARRIVAL);
        request.setDateTo(ARRIVAL.plusDays(3));
        request.setRooms(rooms);
        request.setNights(nights);
        return request;
    }

    @Test
    @SuppressWarnings("unchecked")
    void createFillsEveryNightWithoutPlacing() {
        service.create(5L, request(7L, 4, List.of(new BookingDtos.RoomNight(ARRIVAL.plusDays(1), 10))));

        ArgumentCaptor<List<BookingRoomBlockNight>> saved = ArgumentCaptor.forClass(List.class);
        verify(nightRepo).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(BookingRoomBlockNight::getQty).containsExactly(4, 10, 4);
        ArgumentCaptor<BookingRoomBlock> block = ArgumentCaptor.forClass(BookingRoomBlock.class);
        verify(reservationSync).syncRoomBlock(any(), block.capture());
        assertThat(block.getValue().getPropertyTenantId()).isEqualTo(7L);
        assertThat(block.getValue().getRoomResourceId()).isNull();
        assertThat(block.getValue().getRoomTypeLabel()).isEqualTo("Double for delegates");
        assertThat(block.getValue().getTenantId()).isEqualTo(1L);
    }

    @Test
    void placeRequiresRoomPool() {
        BookingRoomBlock block = BookingRoomBlock.builder().id(21L).tenantId(1L).bookingId(5L)
                .dateFrom(ARRIVAL).dateTo(ARRIVAL.plusDays(2)).build();
        when(blockRepo.findById(21L)).thenReturn(Optional.of(block));
        Resource exact = pool(31L, 7L, "ROOM");
        exact.setKind("EXACT");
        when(resourceRepo.findByIdWithType(31L)).thenReturn(Optional.of(exact));
        when(resourceRepo.findByIdWithType(32L)).thenReturn(Optional.of(pool(32L, 7L, "MEETING_ROOM")));

        assertThatThrownBy(() -> service.place(21L, 31L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.place(21L, 32L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bookingLimitedToHotelRejectsOtherHotel() {
        booking.setPropertyTenantId(7L);

        assertThatThrownBy(() -> service.create(5L, request(8L, 2, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limited to its hotel");
    }

    @Test
    void movingToAnotherHotelDetachesOldLinesFirst() {
        BookingRoomBlock block = BookingRoomBlock.builder().id(21L).tenantId(1L).bookingId(5L)
                .propertyTenantId(7L).roomResourceId(30L).dateFrom(ARRIVAL).dateTo(ARRIVAL.plusDays(3)).build();
        when(blockRepo.findById(21L)).thenReturn(Optional.of(block));
        when(resourceRepo.findByIdWithType(40L)).thenReturn(Optional.of(pool(40L, 8L, "ROOM")));
        ArgumentCaptor<BookingRoomBlock> detached = ArgumentCaptor.forClass(BookingRoomBlock.class);
        List<Long> hotelAtDetach = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            hotelAtDetach.add(((BookingRoomBlock) inv.getArgument(0)).getPropertyTenantId());
            return null;
        }).when(reservationSync).detachRoomBlock(detached.capture());

        service.place(21L, 40L);

        assertThat(hotelAtDetach).containsExactly(7L);
        assertThat(block.getPropertyTenantId()).isEqualTo(8L);
        verify(reservationSync).syncRoomBlock(booking, block);
    }

    @Test
    void nightOutsideStayIsRejected() {
        BookingRoomBlock block = BookingRoomBlock.builder().id(21L).tenantId(1L).bookingId(5L)
                .dateFrom(ARRIVAL).dateTo(ARRIVAL.plusDays(2)).build();
        when(blockRepo.findById(21L)).thenReturn(Optional.of(block));

        assertThatThrownBy(() -> service.setNights(21L, List.of(new BookingDtos.RoomNight(ARRIVAL.plusDays(2), 3))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(reservationSync, never()).syncRoomBlock(any(), any());
    }
}
