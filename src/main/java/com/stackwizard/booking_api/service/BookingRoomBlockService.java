package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.BookingRoomBlockNight;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.BookingRoomBlockNightRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.service.opera.OperaRoomAvailabilityService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rooms of a booking: a quota per room type and night. A block without a room type is only a need;
 * placing it on a hotel's room-type pool turns every night into a reservation line of the booking's request.
 */
@Service
public class BookingRoomBlockService {
    static final int MAX_NIGHTS = 366;

    private final BookingService bookingService;
    private final BookingRoomBlockRepository blockRepo;
    private final BookingRoomBlockNightRepository nightRepo;
    private final ResourceRepository resourceRepo;
    private final ReservationRepository reservationRepo;
    private final OperaHotelRepository operaHotelRepo;
    private final TenantHierarchy hierarchy;
    private final BookingReservationSync reservationSync;
    private final AllocationCapacityService capacityService;
    private final OperaRoomAvailabilityService operaAvailability;

    public BookingRoomBlockService(BookingService bookingService,
                                   BookingRoomBlockRepository blockRepo,
                                   BookingRoomBlockNightRepository nightRepo,
                                   ResourceRepository resourceRepo,
                                   ReservationRepository reservationRepo,
                                   OperaHotelRepository operaHotelRepo,
                                   TenantHierarchy hierarchy,
                                   BookingReservationSync reservationSync,
                                   AllocationCapacityService capacityService,
                                   OperaRoomAvailabilityService operaAvailability) {
        this.bookingService = bookingService;
        this.blockRepo = blockRepo;
        this.nightRepo = nightRepo;
        this.resourceRepo = resourceRepo;
        this.reservationRepo = reservationRepo;
        this.operaHotelRepo = operaHotelRepo;
        this.hierarchy = hierarchy;
        this.reservationSync = reservationSync;
        this.capacityService = capacityService;
        this.operaAvailability = operaAvailability;
    }

    public List<BookingDtos.RoomBlockView> blocks(Long bookingId) {
        Booking booking = bookingService.requireBooking(bookingId);
        return blockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(booking.getTenantId(), booking.getId())
                .stream().map(this::view).toList();
    }

    public List<BookingRoomBlock> rawBlocks(Booking booking) {
        return blockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(booking.getTenantId(), booking.getId());
    }

    @Transactional
    public BookingDtos.RoomBlockView create(Long bookingId, BookingDtos.RoomBlockRequest request) {
        Booking booking = requireOpenForPlanning(bookingId);
        BookingRoomBlock block = BookingRoomBlock.builder()
                .tenantId(booking.getTenantId())
                .bookingId(booking.getId())
                .build();
        apply(booking, block, request);
        BookingRoomBlock saved = blockRepo.save(block);
        writeNights(saved, request.getNights(), request.getRooms() != null ? request.getRooms() : 0);
        reservationSync.syncRoomBlock(booking, saved);
        reservationSync.pushOperaBlock(booking);
        return view(saved);
    }

    @Transactional
    public BookingDtos.RoomBlockView update(Long blockId, BookingDtos.RoomBlockRequest request) {
        BookingRoomBlock block = requireBlock(blockId);
        Booking booking = requireOpenForPlanning(block.getBookingId());
        Long previousResource = block.getRoomResourceId();
        Long previousHotel = block.getPropertyTenantId();
        apply(booking, block, request);
        if (!Objects.equals(previousResource, block.getRoomResourceId())
                || !Objects.equals(previousHotel, block.getPropertyTenantId())) {
            moveLines(block, previousHotel);
        }
        BookingRoomBlock saved = blockRepo.save(block);
        writeNights(saved, request.getNights(), request.getRooms());
        reservationSync.syncRoomBlock(booking, saved);
        reservationSync.pushOperaBlock(booking);
        return view(saved);
    }

    @Transactional
    public BookingDtos.RoomBlockView setNights(Long blockId, List<BookingDtos.RoomNight> nights) {
        BookingRoomBlock block = requireBlock(blockId);
        Booking booking = requireOpenForPlanning(block.getBookingId());
        writeNights(block, nights, null);
        reservationSync.syncRoomBlock(booking, block);
        reservationSync.pushOperaBlock(booking);
        return view(block);
    }

    /**
     * Room types of a hotel with free units for this need. Call when the user is about to place.
     * OPERA hotels use PAR stay availability; LOCAL hotels use allocation.
     */
    @Transactional(readOnly = true)
    public BookingDtos.PlacementAvailability placementAvailability(Long blockId, Long propertyTenantId) {
        BookingRoomBlock block = requireBlock(blockId);
        Booking booking = bookingService.requireBooking(block.getBookingId());
        Long hotelId = propertyTenantId != null ? propertyTenantId
                : block.getPropertyTenantId() != null ? block.getPropertyTenantId()
                : booking.getPropertyTenantId();
        if (hotelId == null) {
            throw new IllegalArgumentException("propertyTenantId is required to check room availability");
        }
        hotelId = hierarchy.requirePropertyOf(hotelId, booking.getTenantId());
        Long limitedTo = booking.getPropertyTenantId();
        if (limitedTo != null && !limitedTo.equals(hotelId)) {
            throw new IllegalArgumentException("The booking is limited to another hotel");
        }
        Map<LocalDate, Integer> quota = reservationSync.quota(block);
        List<BookingDtos.RoomNight> requested = quota.entrySet().stream()
                .map(e -> new BookingDtos.RoomNight(e.getKey(), e.getValue()))
                .toList();
        int maxRooms = quota.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Resource> roomTypes = resourceRepo.findByTenantId(hotelId).stream()
                .filter(ResourceSetupCapacityService::isRoomType)
                .toList();
        Set<Long> own = ownLineIds(block);
        OperaHotel operaHotel = operaHotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(hotelId).stream()
                .filter(h -> !Boolean.FALSE.equals(h.getActive()))
                .filter(h -> h.getRoomInventory() == OperaHotel.Mode.OPERA)
                .findFirst()
                .orElse(null);
        if (operaHotel != null) {
            return operaPlacement(block, operaHotel, hotelId, requested, quota, maxRooms, roomTypes);
        }
        return localPlacement(block, hotelId, requested, quota, roomTypes, own);
    }

    private BookingDtos.PlacementAvailability localPlacement(BookingRoomBlock block,
                                                             Long hotelId,
                                                             List<BookingDtos.RoomNight> requested,
                                                             Map<LocalDate, Integer> quota,
                                                             List<Resource> roomTypes,
                                                             Set<Long> own) {
        List<BookingDtos.PlacementRoomType> rows = new ArrayList<>();
        for (Resource roomType : roomTypes) {
            int capacity = AllocationCapacityService.capacityOf(roomType);
            int lowest = Integer.MAX_VALUE;
            boolean fits = true;
            List<BookingDtos.PlacementNight> nights = new ArrayList<>();
            for (Map.Entry<LocalDate, Integer> night : quota.entrySet()) {
                int free = capacityService.freeUnits(roomType,
                        LocalDateTime.of(night.getKey(), BookingReservationSync.CHECK_IN),
                        LocalDateTime.of(night.getKey().plusDays(1), BookingReservationSync.CHECK_OUT),
                        own);
                lowest = Math.min(lowest, free);
                if (free < night.getValue()) {
                    fits = false;
                }
                nights.add(BookingDtos.PlacementNight.builder()
                        .night(night.getKey())
                        .requested(night.getValue())
                        .free(free)
                        .capacity(capacity)
                        .build());
            }
            rows.add(BookingDtos.PlacementRoomType.builder()
                    .resourceId(roomType.getId())
                    .code(roomType.getCode())
                    .name(roomType.getName())
                    .capacity(capacity)
                    .freeUnits(lowest == Integer.MAX_VALUE ? capacity : lowest)
                    .fits(fits)
                    .placed(Objects.equals(roomType.getId(), block.getRoomResourceId()))
                    .nights(nights)
                    .build());
        }
        return BookingDtos.PlacementAvailability.builder()
                .blockId(block.getId())
                .propertyTenantId(hotelId)
                .source("LOCAL")
                .granularity("NIGHT")
                .dateFrom(block.getDateFrom())
                .dateTo(block.getDateTo())
                .requested(requested)
                .roomTypes(rows)
                .build();
    }

    private BookingDtos.PlacementAvailability operaPlacement(BookingRoomBlock block,
                                                             OperaHotel operaHotel,
                                                             Long hotelId,
                                                             List<BookingDtos.RoomNight> requested,
                                                             Map<LocalDate, Integer> quota,
                                                             int maxRooms,
                                                             List<Resource> roomTypes) {
        Map<String, Map<LocalDate, Integer>> operaFree;
        String warning = null;
        try {
            operaFree = operaAvailability.freeUnitsByRoomTypeAndNight(
                    operaHotel, block.getDateFrom(), block.getDateTo(), maxRooms);
        } catch (RuntimeException ex) {
            operaFree = Map.of();
            warning = "OPERA availability failed: " + ex.getMessage();
        }
        List<BookingDtos.PlacementRoomType> rows = new ArrayList<>();
        for (Resource roomType : roomTypes) {
            String code = OperaRoomAvailabilityService.operaCodeOf(roomType);
            int capacity = AllocationCapacityService.capacityOf(roomType);
            Map<LocalDate, Integer> byNight = code != null ? operaFree.getOrDefault(code, Map.of()) : Map.of();
            boolean offered = !byNight.isEmpty();
            boolean fits = offered;
            int lowest = Integer.MAX_VALUE;
            List<BookingDtos.PlacementNight> nights = new ArrayList<>();
            for (Map.Entry<LocalDate, Integer> night : quota.entrySet()) {
                Integer free = byNight.get(night.getKey());
                if (free != null) {
                    lowest = Math.min(lowest, free);
                }
                if (free == null || free < night.getValue()) {
                    fits = false;
                }
                nights.add(BookingDtos.PlacementNight.builder()
                        .night(night.getKey())
                        .requested(night.getValue())
                        .free(free)
                        .capacity(capacity)
                        .build());
            }
            Integer freeUnits = offered
                    ? (lowest == Integer.MAX_VALUE ? OperaRoomAvailabilityService.minFree(byNight) : lowest)
                    : 0;
            rows.add(BookingDtos.PlacementRoomType.builder()
                    .resourceId(roomType.getId())
                    .code(roomType.getCode())
                    .name(roomType.getName())
                    .capacity(capacity)
                    .freeUnits(freeUnits)
                    .fits(fits)
                    .placed(Objects.equals(roomType.getId(), block.getRoomResourceId()))
                    .nights(nights)
                    .build());
        }
        return BookingDtos.PlacementAvailability.builder()
                .blockId(block.getId())
                .propertyTenantId(hotelId)
                .hotelCode(operaHotel.getHotelCode())
                .source("OPERA")
                .granularity("NIGHT")
                .warning(warning)
                .dateFrom(block.getDateFrom())
                .dateTo(block.getDateTo())
                .requested(requested)
                .roomTypes(rows)
                .build();
    }

    private Set<Long> ownLineIds(BookingRoomBlock block) {
        if (block.getPropertyTenantId() == null) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(block.getPropertyTenantId(), block.getId())
                .forEach(r -> ids.add(r.getId()));
        return ids;
    }

    /** Places the block on a hotel's room type (POOL resource); the lines follow the booking phase. */
    @Transactional
    public BookingDtos.RoomBlockView place(Long blockId, Long roomResourceId) {
        BookingRoomBlock block = requireBlock(blockId);
        Booking booking = requireOpenForPlanning(block.getBookingId());
        Resource roomType = requireRoomType(booking, roomResourceId);
        if (!Objects.equals(block.getRoomResourceId(), roomType.getId())) {
            Long previousHotel = block.getPropertyTenantId();
            block.setRoomResourceId(roomType.getId());
            block.setPropertyTenantId(roomType.getTenantId());
            moveLines(block, previousHotel);
        }
        BookingRoomBlock saved = blockRepo.save(block);
        reservationSync.syncRoomBlock(booking, saved);
        reservationSync.pushOperaBlock(booking);
        return view(saved);
    }

    @Transactional
    public BookingDtos.RoomBlockView unplace(Long blockId) {
        BookingRoomBlock block = requireBlock(blockId);
        Booking booking = requireOpenForPlanning(block.getBookingId());
        reservationSync.detachRoomBlock(block);
        block.setRoomResourceId(null);
        BookingRoomBlock saved = blockRepo.save(block);
        reservationSync.pushOperaBlock(booking);
        return view(saved);
    }

    @Transactional
    public void delete(Long blockId) {
        BookingRoomBlock block = requireBlock(blockId);
        Booking booking = requireOpenForPlanning(block.getBookingId());
        if (booking.getStatus() == Booking.Status.DEFINITE && block.getRoomResourceId() != null) {
            throw new IllegalStateException("Placed room blocks of a DEFINITE booking cannot be deleted; set the quota to 0 instead");
        }
        reservationSync.detachRoomBlock(block);
        blockRepo.delete(block);
    }

    private void moveLines(BookingRoomBlock block, Long previousHotel) {
        Long current = block.getPropertyTenantId();
        block.setPropertyTenantId(previousHotel);
        reservationSync.detachRoomBlock(block);
        block.setPropertyTenantId(current);
    }

    private void apply(Booking booking, BookingRoomBlock block, BookingDtos.RoomBlockRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (request.getDateFrom() == null || request.getDateTo() == null || !request.getDateTo().isAfter(request.getDateFrom())) {
            throw new IllegalArgumentException("dateTo (departure) must be after dateFrom");
        }
        if (ChronoUnit.DAYS.between(request.getDateFrom(), request.getDateTo()) > MAX_NIGHTS) {
            throw new IllegalArgumentException("A room block can span at most " + MAX_NIGHTS + " nights");
        }
        if (request.getPaxPerRoom() != null && request.getPaxPerRoom() <= 0) {
            throw new IllegalArgumentException("paxPerRoom must be positive");
        }
        if (request.getRooms() != null && request.getRooms() < 0) {
            throw new IllegalArgumentException("rooms cannot be negative");
        }
        block.setDateFrom(request.getDateFrom());
        block.setDateTo(request.getDateTo());
        block.setRoomTypeLabel(trim(request.getRoomTypeLabel()));
        block.setPaxPerRoom(request.getPaxPerRoom() != null ? request.getPaxPerRoom() : 2);
        block.setRatePriceProfileId(request.getRatePriceProfileId());
        block.setNotes(trim(request.getNotes()));
        // Placement is only via place()/unplace(); create/update keep the need (label, dates, quota, preferred hotel).
        if (block.getRoomResourceId() == null) {
            block.setPropertyTenantId(request.getPropertyTenantId() == null ? null
                    : hierarchy.requirePropertyOf(request.getPropertyTenantId(), booking.getTenantId()));
        } else if (request.getPropertyTenantId() != null
                && !request.getPropertyTenantId().equals(block.getPropertyTenantId())) {
            throw new IllegalArgumentException("Unplace the room type before moving the need to another hotel");
        }
        Long limitedTo = booking.getPropertyTenantId();
        if (limitedTo != null && block.getPropertyTenantId() != null && !limitedTo.equals(block.getPropertyTenantId())) {
            throw new IllegalArgumentException("The booking is limited to its hotel; this room type is in another hotel");
        }
    }

    /** Replaces the quota of the given nights; {@code fill} sets every other night of the stay, null keeps it. */
    private void writeNights(BookingRoomBlock block, List<BookingDtos.RoomNight> nights, Integer fill) {
        Map<LocalDate, Integer> wanted = new HashMap<>();
        if (nights != null) {
            for (BookingDtos.RoomNight night : nights) {
                if (night.getNight() == null || night.getQty() == null || night.getQty() < 0) {
                    throw new IllegalArgumentException("Every night needs a date and a quota >= 0");
                }
                if (night.getNight().isBefore(block.getDateFrom()) || !night.getNight().isBefore(block.getDateTo())) {
                    throw new IllegalArgumentException("Night " + night.getNight() + " is outside the block stay");
                }
                wanted.put(night.getNight(), night.getQty());
            }
        }
        Map<LocalDate, BookingRoomBlockNight> existing = new HashMap<>();
        List<BookingRoomBlockNight> outside = new ArrayList<>();
        for (BookingRoomBlockNight row : nightRepo.findByTenantIdAndBlockIdOrderByNightAsc(block.getTenantId(), block.getId())) {
            if (row.getNight().isBefore(block.getDateFrom()) || !row.getNight().isBefore(block.getDateTo())) {
                outside.add(row);
            } else {
                existing.put(row.getNight(), row);
            }
        }
        nightRepo.deleteAll(outside);
        List<BookingRoomBlockNight> save = new ArrayList<>();
        for (LocalDate night = block.getDateFrom(); night.isBefore(block.getDateTo()); night = night.plusDays(1)) {
            Integer qty = wanted.containsKey(night) ? wanted.get(night)
                    : fill != null ? fill
                    : existing.containsKey(night) ? null : Integer.valueOf(0);
            if (qty == null) {
                continue;
            }
            BookingRoomBlockNight row = existing.get(night);
            if (row == null) {
                row = BookingRoomBlockNight.builder().tenantId(block.getTenantId()).blockId(block.getId()).night(night).build();
            }
            row.setQty(qty);
            save.add(row);
        }
        nightRepo.saveAll(save);
        nightRepo.flush();
    }

    private Resource requireRoomType(Booking booking, Long roomResourceId) {
        Resource resource = resourceRepo.findByIdWithType(roomResourceId)
                .filter(r -> hierarchy.isPropertyOf(r.getTenantId(), booking.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Room type not found: " + roomResourceId));
        String type = resource.getResourceType() != null ? resource.getResourceType().getCode() : null;
        if (!AllocationCapacityService.isPool(resource)
                || type == null || !ResourceSetupCapacityService.ROOM_RESOURCE_TYPES.contains(type.toUpperCase())) {
            throw new IllegalArgumentException("Resource " + resource.getName() + " is not a room-type pool ("
                    + ResourceSetupCapacityService.ROOM_RESOURCE_TYPES + ")");
        }
        return resource;
    }

    private BookingRoomBlock requireBlock(Long blockId) {
        BookingRoomBlock block = blockRepo.findById(blockId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room block not found"));
        Booking booking = bookingService.requireBooking(block.getBookingId());
        if (!booking.getTenantId().equals(block.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room block not found");
        }
        return block;
    }

    private Booking requireOpenForPlanning(Long bookingId) {
        return bookingService.requireContentEditable(bookingId);
    }

    BookingDtos.RoomBlockView view(BookingRoomBlock block) {
        List<BookingDtos.RoomNight> nights = reservationSync.quota(block).entrySet().stream()
                .map(e -> new BookingDtos.RoomNight(e.getKey(), e.getValue()))
                .toList();
        List<Reservation> reservations = block.getPropertyTenantId() == null ? List.of()
                : reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(block.getPropertyTenantId(), block.getId());
        List<BookingDtos.RoomLine> lines = reservations.stream()
                .map(r -> BookingDtos.RoomLine.builder()
                        .reservationId(r.getId())
                        .night(r.getStartsAt().toLocalDate())
                        .units(r.getUnits())
                        .status(r.getStatus())
                        .amount(r.getGrossAmount())
                        .build())
                .toList();
        BigDecimal amount = reservations.stream()
                .filter(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()))
                .map(r -> r.getGrossAmount() != null ? r.getGrossAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String resourceName = block.getRoomResourceId() == null ? null
                : resourceRepo.findById(block.getRoomResourceId()).map(Resource::getName).orElse(null);
        return BookingDtos.RoomBlockView.builder()
                .block(block)
                .roomResourceName(resourceName)
                .nights(nights)
                .roomNights(nights.stream().mapToInt(BookingDtos.RoomNight::getQty).sum())
                .amount(amount)
                .lines(lines)
                .build();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
