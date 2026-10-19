package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where can the booking go: for every hotel of the chain (or the booking's hotel when it is limited) and every need,
 * the spaces or room types that fit. The booking's own lines do not count as occupying, so a placed need still
 * shows its current resource as available.
 */
@Service
public class BookingFitService {
    private final BookingService bookingService;
    private final BookingFunctionRepository functionRepo;
    private final BookingRoomBlockRepository roomBlockRepo;
    private final ReservationRepository reservationRepo;
    private final ResourceRepository resourceRepo;
    private final PlatformTenantMappingRepository mappingRepo;
    private final TenantHierarchy hierarchy;
    private final ResourceSetupCapacityService spaceService;
    private final AllocationCapacityService capacityService;
    private final BookingReservationSync reservationSync;

    public BookingFitService(BookingService bookingService,
                             BookingFunctionRepository functionRepo,
                             BookingRoomBlockRepository roomBlockRepo,
                             ReservationRepository reservationRepo,
                             ResourceRepository resourceRepo,
                             PlatformTenantMappingRepository mappingRepo,
                             TenantHierarchy hierarchy,
                             ResourceSetupCapacityService spaceService,
                             AllocationCapacityService capacityService,
                             BookingReservationSync reservationSync) {
        this.bookingService = bookingService;
        this.functionRepo = functionRepo;
        this.roomBlockRepo = roomBlockRepo;
        this.reservationRepo = reservationRepo;
        this.resourceRepo = resourceRepo;
        this.mappingRepo = mappingRepo;
        this.hierarchy = hierarchy;
        this.spaceService = spaceService;
        this.capacityService = capacityService;
        this.reservationSync = reservationSync;
    }

    @Transactional(readOnly = true)
    public BookingDtos.FitResult fit(Long bookingId) {
        Booking booking = bookingService.requireBooking(bookingId);
        Long org = booking.getTenantId();
        List<BookingFunction> functions = functionRepo.findForBooking(org, booking.getId());
        List<BookingRoomBlock> blocks = roomBlockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(org, booking.getId());
        Set<Long> own = ownReservationIds(functions, blocks);

        List<Long> hotels = booking.getPropertyTenantId() != null
                ? List.of(booking.getPropertyTenantId())
                : hierarchy.propertyIdsOf(org);
        Map<Long, PlatformTenantMapping> names = mappingRepo.findByTenantIdIn(hotels).stream()
                .collect(Collectors.toMap(PlatformTenantMapping::getTenantId, Function.identity(), (a, b) -> a));

        List<BookingDtos.FitHotel> out = new ArrayList<>();
        for (Long hotel : hotels) {
            List<BookingDtos.FitNeed> needs = new ArrayList<>();
            for (BookingFunction function : functions) {
                needs.add(functionNeed(hotel, function, own));
            }
            List<Resource> roomTypes = blocks.isEmpty() ? List.of() : roomTypes(hotel);
            for (BookingRoomBlock block : blocks) {
                needs.add(roomNeed(block, roomTypes, own));
            }
            int fitting = (int) needs.stream().filter(BookingDtos.FitNeed::isFits).count();
            PlatformTenantMapping mapping = names.get(hotel);
            out.add(BookingDtos.FitHotel.builder()
                    .tenantId(hotel)
                    .name(mapping != null ? mapping.getName() : null)
                    .hotelCode(mapping != null ? mapping.getHotelCode() : null)
                    .needs(needs)
                    .fittingNeeds(fitting)
                    .fitsAll(!needs.isEmpty() && fitting == needs.size())
                    .build());
        }
        return BookingDtos.FitResult.builder().bookingId(booking.getId()).hotels(out).build();
    }

    private BookingDtos.FitNeed functionNeed(Long hotel, BookingFunction function, Set<Long> own) {
        LocalDateTime from = function.getOccupancyStartsAt() != null ? function.getOccupancyStartsAt() : function.getStartsAt();
        LocalDateTime to = function.getOccupancyEndsAt() != null ? function.getOccupancyEndsAt() : function.getEndsAt();
        String setup = function.getSetupStyle();
        boolean bySeats = function.getPax() != null || (setup != null && !setup.isBlank());
        List<ResourceSetupCapacityService.SpaceCandidate> spaces = bySeats
                ? spaceService.searchSpaces(hotel, function.getPax(), setup, from, to, own)
                : spaceService.listSpaces(hotel, from, to, own);
        List<BookingDtos.FitCandidate> candidates = spaces.stream()
                .map(c -> BookingDtos.FitCandidate.builder()
                        .resourceId(c.resourceId())
                        .name(c.name())
                        .resourceType(c.resourceType())
                        .capacity(seats(c, setup))
                        .available(c.available())
                        .placed(Objects.equals(c.resourceId(), function.getResourceId()))
                        .build())
                .toList();
        return BookingDtos.FitNeed.builder()
                .kind("FUNCTION")
                .needId(function.getId())
                .label(function.getName() != null ? function.getName() : String.valueOf(function.getFunctionType()))
                .startsAt(from)
                .endsAt(to)
                .pax(function.getPax())
                .setupStyle(setup)
                .placedTenantId(function.getResourceId() != null ? function.getTenantId() : null)
                .placedResourceId(function.getResourceId())
                .fits(candidates.stream().anyMatch(BookingDtos.FitCandidate::isAvailable))
                .candidates(candidates)
                .build();
    }

    private BookingDtos.FitNeed roomNeed(BookingRoomBlock block, List<Resource> roomTypes, Set<Long> own) {
        Map<LocalDate, Integer> quota = reservationSync.quota(block);
        int rooms = quota.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<BookingDtos.FitCandidate> candidates = new ArrayList<>();
        for (Resource roomType : roomTypes) {
            int lowest = Integer.MAX_VALUE;
            boolean available = true;
            for (Map.Entry<LocalDate, Integer> night : quota.entrySet()) {
                int free = capacityService.freeUnits(roomType,
                        LocalDateTime.of(night.getKey(), BookingReservationSync.CHECK_IN),
                        LocalDateTime.of(night.getKey().plusDays(1), BookingReservationSync.CHECK_OUT),
                        own);
                lowest = Math.min(lowest, free);
                if (free < night.getValue()) {
                    available = false;
                }
            }
            candidates.add(BookingDtos.FitCandidate.builder()
                    .resourceId(roomType.getId())
                    .name(roomType.getName())
                    .resourceType(roomType.getResourceType() != null ? roomType.getResourceType().getCode() : null)
                    .capacity(AllocationCapacityService.capacityOf(roomType))
                    .freeUnits(lowest == Integer.MAX_VALUE ? AllocationCapacityService.capacityOf(roomType) : lowest)
                    .available(available)
                    .placed(Objects.equals(roomType.getId(), block.getRoomResourceId()))
                    .build());
        }
        return BookingDtos.FitNeed.builder()
                .kind("ROOM_BLOCK")
                .needId(block.getId())
                .label(block.getRoomTypeLabel() != null ? block.getRoomTypeLabel() : "Rooms")
                .startsAt(LocalDateTime.of(block.getDateFrom(), BookingReservationSync.CHECK_IN))
                .endsAt(LocalDateTime.of(block.getDateTo(), BookingReservationSync.CHECK_OUT))
                .pax(block.getPaxPerRoom())
                .rooms(rooms)
                .placedTenantId(block.getRoomResourceId() != null ? block.getPropertyTenantId() : null)
                .placedResourceId(block.getRoomResourceId())
                .fits(candidates.stream().anyMatch(BookingDtos.FitCandidate::isAvailable))
                .candidates(candidates)
                .build();
    }

    /** Active room-type pools of a hotel. */
    List<Resource> roomTypes(Long hotel) {
        return resourceRepo.findByTenantId(hotel).stream().filter(ResourceSetupCapacityService::isRoomType).toList();
    }

    private Set<Long> ownReservationIds(List<BookingFunction> functions, List<BookingRoomBlock> blocks) {
        Set<Long> ids = new HashSet<>();
        reservationSync.activeLines(functions).forEach(r -> ids.add(r.getId()));
        for (BookingRoomBlock block : blocks) {
            if (block.getPropertyTenantId() != null) {
                reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(block.getPropertyTenantId(), block.getId())
                        .stream().map(Reservation::getId).forEach(ids::add);
            }
        }
        return ids;
    }

    private static Integer seats(ResourceSetupCapacityService.SpaceCandidate candidate, String setup) {
        return candidate.setups().stream()
                .filter(s -> setup == null || setup.isBlank() || setup.equalsIgnoreCase(s.setupStyle()))
                .map(ResourceSetupCapacityService.SetupCapacity::capacity)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }
}
