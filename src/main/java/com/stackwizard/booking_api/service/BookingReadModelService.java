package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingFunctionItem;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
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
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.opera.OperaRoomAvailabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BookingReadModelService {
    private static final Logger log = LoggerFactory.getLogger(BookingReadModelService.class);

    private final BookingService bookingService;
    private final BookingRepository bookingRepo;
    private final BookingFunctionRepository functionRepo;
    private final BookingFunctionItemRepository itemRepo;
    private final BookingReservationSync reservationSync;
    private final ResourceRepository resourceRepo;
    private final AllocationRepository allocationRepo;
    private final CrmAccessContext accessContext;
    private final BookingRoomBlockRepository roomBlockRepo;
    private final ReservationRepository reservationRepo;
    private final ReservationRequestRepository requestRepo;
    private final PlatformTenantMappingRepository mappingRepo;
    private final TenantHierarchy hierarchy;
    private final OperaHotelRepository operaHotelRepo;
    private final OperaRoomAvailabilityService operaAvailability;

    public BookingReadModelService(BookingService bookingService,
                                 BookingRepository bookingRepo,
                                 BookingFunctionRepository functionRepo,
                                 BookingFunctionItemRepository itemRepo,
                                 BookingReservationSync reservationSync,
                                 ResourceRepository resourceRepo,
                                 AllocationRepository allocationRepo,
                                 CrmAccessContext accessContext,
                                 BookingRoomBlockRepository roomBlockRepo,
                                 ReservationRepository reservationRepo,
                                 ReservationRequestRepository requestRepo,
                                 PlatformTenantMappingRepository mappingRepo,
                                 TenantHierarchy hierarchy,
                                 OperaHotelRepository operaHotelRepo,
                                 OperaRoomAvailabilityService operaAvailability) {
        this.bookingService = bookingService;
        this.bookingRepo = bookingRepo;
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.reservationSync = reservationSync;
        this.resourceRepo = resourceRepo;
        this.allocationRepo = allocationRepo;
        this.accessContext = accessContext;
        this.roomBlockRepo = roomBlockRepo;
        this.reservationRepo = reservationRepo;
        this.requestRepo = requestRepo;
        this.mappingRepo = mappingRepo;
        this.hierarchy = hierarchy;
        this.operaHotelRepo = operaHotelRepo;
        this.operaAvailability = operaAvailability;
    }

    @Transactional(readOnly = true)
    public List<BookingDtos.BookingSummary> opportunitySummaries(Long opportunityId) {
        List<BookingDtos.BookingSummary> result = new ArrayList<>();
        for (Booking booking : bookingService.forOpportunity(opportunityId)) {
            result.add(summary(booking));
        }
        return result;
    }

    BookingDtos.BookingSummary summary(Booking booking) {
        List<BookingFunction> functions = functionRepo.findForBooking(booking.getTenantId(), booking.getId());
        List<BookingRoomBlock> blocks = roomBlockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(
                booking.getTenantId(), booking.getId());
        boolean hasItems = !functions.isEmpty() && !itemRepo.findForFunctions(booking.getTenantId(),
                functions.stream().map(BookingFunction::getId).toList()).isEmpty();
        Set<Long> hotelIds = new LinkedHashSet<>();
        functions.stream().map(BookingFunction::getTenantId)
                .filter(id -> id != null && !id.equals(booking.getTenantId()))
                .forEach(hotelIds::add);
        blocks.stream().map(BookingRoomBlock::getPropertyTenantId).filter(id -> id != null).forEach(hotelIds::add);
        Map<Long, String> names = hotelNames(hotelIds);
        return BookingDtos.BookingSummary.builder()
                .bookingId(booking.getId())
                .kind(kindOf(functions.size(), blocks.size(), hasItems))
                .functionCount(functions.size())
                .roomBlockCount(blocks.size())
                .hotels(hotelIds.stream()
                        .map(id -> BookingDtos.SummaryHotel.builder().tenantId(id).hotelName(names.get(id)).build())
                        .toList())
                .build();
    }

    static String kindOf(int functions, int roomBlocks, boolean hasItems) {
        if (functions > 0 && roomBlocks > 0) {
            return "CONFERENCE";
        }
        if (roomBlocks > 0) {
            return "GROUP";
        }
        if (functions == 1 && !hasItems) {
            return "SPACE_RENTAL";
        }
        return functions > 0 ? "EVENT" : "EMPTY";
    }

    @Transactional(readOnly = true)
    public BookingDtos.Financials financials(Long bookingId) {
        Booking booking = bookingService.requireBooking(bookingId);
        List<BookingFunction> functions = functionRepo.findForBooking(
                booking.getTenantId(), booking.getId());
        List<Long> functionIds = functions.stream().map(BookingFunction::getId).toList();
        Map<Long, Reservation> rentByFunction = reservationSync.activeLines(functions).stream()
                .collect(Collectors.toMap(Reservation::getBookingFunctionId, Function.identity(), (a, b) -> a));
        Map<Long, List<BookingFunctionItem>> itemsByFunction = functionIds.isEmpty() ? Map.of()
                : itemRepo.findForFunctions(booking.getTenantId(), functionIds).stream()
                .collect(Collectors.groupingBy(BookingFunctionItem::getBookingFunctionId));
        Map<Long, String> resourceNames = resourceNames(functions.stream().map(BookingFunction::getResourceId).toList());

        BigDecimal rentTotal = BigDecimal.ZERO;
        BigDecimal itemsTotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        Map<Long, BigDecimal[]> byHotel = new TreeMap<>();
        List<BookingDtos.FunctionFinancials> rows = new ArrayList<>();
        for (BookingFunction function : functions) {
            Reservation rent = rentByFunction.get(function.getId());
            BigDecimal rentAmount = rent != null && rent.getGrossAmount() != null ? rent.getGrossAmount() : BigDecimal.ZERO;
            List<BookingFunctionItem> items = itemsByFunction.getOrDefault(function.getId(), List.of());
            BigDecimal itemAmount = items.stream().map(BookingFunctionItem::getGrossAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal itemCost = items.stream()
                    .map(i -> i.getCostAmount() != null ? i.getCostAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            rentTotal = rentTotal.add(rentAmount);
            itemsTotal = itemsTotal.add(itemAmount);
            costTotal = costTotal.add(itemCost);
            BigDecimal[] hotel = byHotel.computeIfAbsent(function.getTenantId(), k -> zeros());
            hotel[0] = hotel[0].add(rentAmount);
            hotel[1] = hotel[1].add(itemAmount);
            rows.add(BookingDtos.FunctionFinancials.builder()
                    .functionId(function.getId())
                    .name(function.getName())
                    .functionType(function.getFunctionType().name())
                    .resourceId(function.getResourceId())
                    .resourceName(resourceNames.get(function.getResourceId()))
                    .rentReservationId(rent != null ? rent.getId() : null)
                    .rentStatus(rent != null ? rent.getStatus() : null)
                    .rentUom(rent != null ? rent.getUom() : null)
                    .rentQty(rent != null ? rent.getQty() : null)
                    .rentUnitPrice(rent != null ? rent.getUnitPrice() : null)
                    .rentAmount(money(rentAmount))
                    .itemsAmount(money(itemAmount))
                    .costAmount(money(itemCost))
                    .build());
        }
        BigDecimal roomsTotal = BigDecimal.ZERO;
        for (BookingRoomBlock block : roomBlockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(booking.getTenantId(), booking.getId())) {
            if (block.getPropertyTenantId() == null) {
                continue;
            }
            BigDecimal amount = reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(block.getPropertyTenantId(), block.getId())
                    .stream()
                    .filter(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()))
                    .map(r -> r.getGrossAmount() != null ? r.getGrossAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            roomsTotal = roomsTotal.add(amount);
            BigDecimal[] hotel = byHotel.computeIfAbsent(block.getPropertyTenantId(), k -> zeros());
            hotel[2] = hotel[2].add(amount);
        }
        Map<Long, ReservationRequest> requests = requestRepo.findByBookingIdOrderByTenantIdAsc(booking.getId()).stream()
                .collect(Collectors.toMap(ReservationRequest::getTenantId, Function.identity(), (a, b) -> a));
        Map<Long, String> hotelNames = hotelNames(byHotel.keySet());
        List<BookingDtos.HotelFinancials> hotels = byHotel.entrySet().stream()
                .map(e -> {
                    ReservationRequest request = requests.get(e.getKey());
                    BigDecimal[] v = e.getValue();
                    return BookingDtos.HotelFinancials.builder()
                            .tenantId(e.getKey())
                            .hotelName(hotelNames.get(e.getKey()))
                            .reservationRequestId(request != null ? request.getId() : null)
                            .requestStatus(request != null ? request.getStatus().name() : null)
                            .rentAmount(money(v[0]))
                            .itemsAmount(money(v[1]))
                            .roomsAmount(money(v[2]))
                            .total(money(v[0].add(v[1]).add(v[2])))
                            .build();
                })
                .toList();
        BigDecimal total = rentTotal.add(itemsTotal).add(roomsTotal);
        return BookingDtos.Financials.builder()
                .currency(booking.getCurrency())
                .rentTotal(money(rentTotal))
                .itemsTotal(money(itemsTotal))
                .total(money(total))
                .costTotal(money(costTotal))
                .margin(money(total.subtract(costTotal)))
                .billablePax(BookingItemPricing.billablePax(booking))
                .roomsTotal(money(roomsTotal))
                .functions(rows)
                .hotels(hotels)
                .build();
    }

    /**
     * Function-room occupancy for the date range: active allocations (booking lines and ordinary
     * reservations) plus INQUIRY functions that do not hold the space yet.
     */
    @Transactional(readOnly = true)
    public List<BookingDtos.SpaceGridRow> spaceGrid(LocalDate from, LocalDate to, Long locationId, String scope) {
        accessContext.require(CrmPermission.EVENT_READ);
        requireRange(from, to);
        Long orgTenantId = TenantResolver.requireOrgTenantId();
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        boolean chain = isChain(scope);
        List<Long> hotels = hotelsInScope(chain, orgTenantId);
        Map<Long, String> names = hotelNames(hotels);
        List<BookingDtos.SpaceGridRow> rows = new ArrayList<>();
        for (Long hotel : hotels) {
            for (BookingDtos.SpaceGridRow row : spaceGridFor(hotel, orgTenantId, start, end, chain ? null : locationId)) {
                row.setTenantId(hotel);
                row.setHotelName(names.get(hotel));
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Room-type pools per night. {@code room_inventory=OPERA} → OHIP hotelInventory free counts;
     * otherwise local allocations. {@code blocked} is always our booking room-block holds (tooltip).
     *
     * @param includeTentative Opera only: when true, TEN/allotment reduces free counts (default for sales).
     */
    @Transactional(readOnly = true)
    public List<BookingDtos.RoomGridRow> roomGrid(LocalDate from, LocalDate to, String scope, boolean includeTentative) {
        accessContext.require(CrmPermission.EVENT_READ);
        requireRange(from, to);
        Long orgTenantId = TenantResolver.requireOrgTenantId();
        List<Long> hotels = hotelsInScope(isChain(scope), orgTenantId);
        Map<Long, String> names = hotelNames(hotels);
        List<BookingDtos.RoomGridRow> rows = new ArrayList<>();
        LocalDateTime windowStart = LocalDateTime.of(from, BookingReservationSync.CHECK_IN);
        LocalDateTime windowEnd = LocalDateTime.of(to.plusDays(1), BookingReservationSync.CHECK_OUT);
        for (Long hotel : hotels) {
            List<Resource> roomTypes = resourceRepo.findByTenantId(hotel).stream()
                    .filter(ResourceSetupCapacityService::isRoomType)
                    .sorted(Comparator.comparing(Resource::getDisplayOrder, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(Resource::getName))
                    .toList();
            OperaHotel operaHotel = operaRoomHotel(hotel);
            Map<String, Map<LocalDate, Integer>> operaFree = null;
            if (operaHotel != null) {
                try {
                    operaFree = operaAvailability.freeUnitsByRoomTypeAndNight(
                            operaHotel, from, to.plusDays(1), 1, includeTentative);
                } catch (RuntimeException ex) {
                    log.warn("OPERA room grid failed hotel={}: {}", operaHotel.getHotelCode(), ex.getMessage());
                    operaFree = Map.of();
                }
            }
            List<Reservation> roomBlockLines = reservationRepo.findActiveRoomBlockLinesInWindow(
                    hotel, windowStart, windowEnd);
            for (Resource roomType : roomTypes) {
                int capacity = AllocationCapacityService.capacityOf(roomType);
                List<Reservation> ourBlocks = roomBlockLines.stream()
                        .filter(r -> r.getRequestedResource() != null
                                && roomType.getId().equals(r.getRequestedResource().getId()))
                        .toList();
                List<BookingDtos.RoomGridNight> nights;
                if (operaHotel != null) {
                    nights = operaRoomNights(from, to, capacity, roomType, operaFree, ourBlocks);
                } else {
                    nights = localRoomNights(from, to, capacity, roomType, ourBlocks, windowStart, windowEnd);
                }
                rows.add(BookingDtos.RoomGridRow.builder()
                        .tenantId(hotel)
                        .hotelName(names.get(hotel))
                        .resourceId(roomType.getId())
                        .resourceName(roomType.getName())
                        .resourceType(roomType.getResourceType().getCode())
                        .totalUnits(capacity)
                        .nights(nights)
                        .build());
            }
        }
        return rows;
    }

    private OperaHotel operaRoomHotel(Long hotelTenantId) {
        return operaHotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(hotelTenantId).stream()
                .filter(h -> !Boolean.FALSE.equals(h.getActive()))
                .filter(h -> h.getRoomInventory() == OperaHotel.Mode.OPERA)
                .findFirst()
                .orElse(null);
    }

    private List<BookingDtos.RoomGridNight> operaRoomNights(LocalDate from, LocalDate to, int capacity,
                                                            Resource roomType,
                                                            Map<String, Map<LocalDate, Integer>> operaFree,
                                                            List<Reservation> ourBlocks) {
        String code = OperaRoomAvailabilityService.operaCodeOf(roomType);
        Map<LocalDate, Integer> byNight = code != null && operaFree != null
                ? operaFree.getOrDefault(code, Map.of()) : Map.of();
        List<BookingDtos.RoomGridNight> nights = new ArrayList<>();
        for (LocalDate night = from; !night.isAfter(to); night = night.plusDays(1)) {
            LocalDateTime nightStart = LocalDateTime.of(night, BookingReservationSync.CHECK_IN);
            LocalDateTime nightEnd = LocalDateTime.of(night.plusDays(1), BookingReservationSync.CHECK_OUT);
            int blocked = roomBlockUnits(ourBlocks, nightStart, nightEnd);
            Integer opera = byNight.get(night);
            int free = opera != null ? Math.max(0, opera) : Math.max(0, capacity - blocked);
            int booked = opera != null ? Math.max(0, capacity - free) : blocked;
            nights.add(BookingDtos.RoomGridNight.builder()
                    .night(night)
                    .booked(booked)
                    .blocked(blocked)
                    .free(free)
                    .build());
        }
        return nights;
    }

    private List<BookingDtos.RoomGridNight> localRoomNights(LocalDate from, LocalDate to, int capacity,
                                                            Resource roomType,
                                                            List<Reservation> ourBlocks,
                                                            LocalDateTime windowStart, LocalDateTime windowEnd) {
        List<Allocation> held = allocationRepo.findActiveOnPoolOrMembers(roomType.getId(), windowEnd, windowStart);
        List<Allocation> blocks = held.stream()
                .filter(a -> a.getReservation() != null && a.getReservation().getBookingRoomBlockId() != null)
                .toList();
        Set<Long> reservedWithAlloc = held.stream()
                .map(Allocation::getReservation)
                .filter(Objects::nonNull)
                .map(Reservation::getId)
                .collect(Collectors.toSet());
        List<Reservation> orphan = ourBlocks.stream()
                .filter(r -> !reservedWithAlloc.contains(r.getId()))
                .toList();
        List<BookingDtos.RoomGridNight> nights = new ArrayList<>();
        for (LocalDate night = from; !night.isAfter(to); night = night.plusDays(1)) {
            LocalDateTime nightStart = LocalDateTime.of(night, BookingReservationSync.CHECK_IN);
            LocalDateTime nightEnd = LocalDateTime.of(night.plusDays(1), BookingReservationSync.CHECK_OUT);
            int extra = roomBlockUnits(orphan, nightStart, nightEnd);
            int booked = AllocationCapacityService.peakUnits(held, nightStart, nightEnd) + extra;
            int blocked = AllocationCapacityService.peakUnits(blocks, nightStart, nightEnd) + extra;
            nights.add(BookingDtos.RoomGridNight.builder()
                    .night(night)
                    .booked(booked)
                    .blocked(blocked)
                    .free(Math.max(0, capacity - booked))
                    .build());
        }
        return nights;
    }

    private static int roomBlockUnits(List<Reservation> lines, LocalDateTime nightStart, LocalDateTime nightEnd) {
        int units = 0;
        for (Reservation line : lines) {
            if (line.getStartsAt() == null || line.getEndsAt() == null) {
                continue;
            }
            if (!line.getStartsAt().isBefore(nightEnd) || !line.getEndsAt().isAfter(nightStart)) {
                continue;
            }
            units += line.getUnits() != null && line.getUnits() > 0 ? line.getUnits() : 1;
        }
        return units;
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("from and to are required and to must not be before from");
        }
        if (from.plusDays(62).isBefore(to)) {
            throw new IllegalArgumentException("Range is limited to 62 days");
        }
    }

    private static boolean isChain(String scope) {
        if (scope == null || scope.isBlank() || "hotel".equalsIgnoreCase(scope)) {
            return false;
        }
        if ("chain".equalsIgnoreCase(scope)) {
            return true;
        }
        throw new IllegalArgumentException("scope must be hotel or chain");
    }

    /** Chain scope: the selected hotel when one is selected, otherwise every hotel of the chain. */
    private List<Long> hotelsInScope(boolean chain, Long orgTenantId) {
        if (!chain) {
            return List.of(TenantResolver.requireTenantId());
        }
        Long selected = TenantResolver.selectedHotelId();
        return selected != null ? List.of(selected) : hierarchy.propertyIdsOf(orgTenantId);
    }

    private Map<Long, String> hotelNames(Collection<Long> tenantIds) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (PlatformTenantMapping m : mappingRepo.findByTenantIdIn(tenantIds)) {
            names.put(m.getTenantId(), m.getName() != null ? m.getName() : m.getHotelCode());
        }
        return names;
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private List<BookingDtos.SpaceGridRow> spaceGridFor(Long tenantId, Long orgTenantId, LocalDateTime start,
                                                        LocalDateTime end, Long locationId) {
        List<Resource> spaces = (locationId != null
                ? resourceRepo.findByTenantIdAndLocationId(tenantId, locationId)
                : resourceRepo.findByTenantId(tenantId)).stream()
                .filter(r -> r.getResourceType() != null && r.getResourceType().getCode() != null
                        && ResourceSetupCapacityService.SPACE_RESOURCE_TYPES.contains(
                        r.getResourceType().getCode().toUpperCase(Locale.ROOT)))
                .sorted(Comparator.comparing(Resource::getDisplayOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Resource::getName))
                .toList();
        if (spaces.isEmpty()) {
            return List.of();
        }
        List<Long> spaceIds = spaces.stream().map(Resource::getId).toList();
        Map<Long, List<BookingDtos.SpaceGridBooking>> bookings = new HashMap<>();

        List<Allocation> allocations = allocationRepo.findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(
                spaceIds, end, start);
        Set<Long> functionIds = new HashSet<>();
        for (Allocation allocation : allocations) {
            if (allocation.getReservation().getBookingFunctionId() != null) {
                functionIds.add(allocation.getReservation().getBookingFunctionId());
            }
        }
        List<BookingFunction> planned = functionRepo
                .findByTenantIdAndResourceIdIsNotNullAndOccupancyStartsAtLessThanAndOccupancyEndsAtGreaterThan(tenantId, end, start);
        planned.forEach(f -> functionIds.add(f.getId()));
        Map<Long, BookingFunction> functionsById = functionIds.isEmpty() ? Map.of()
                : functionRepo.findByTenantIdAndIdIn(tenantId, functionIds).stream()
                .collect(Collectors.toMap(BookingFunction::getId, Function.identity()));
        Set<Long> bookingIds = functionsById.values().stream().map(BookingFunction::getBookingId).collect(Collectors.toSet());
        Map<Long, Booking> bookingsById = bookingIds.isEmpty() ? Map.of()
                : bookingRepo.findAllById(bookingIds).stream()
                .filter(e -> orgTenantId.equals(e.getTenantId()))
                .collect(Collectors.toMap(Booking::getId, Function.identity()));

        Set<Long> functionsWithAllocation = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (Allocation allocation : allocations) {
            Reservation reservation = allocation.getReservation();
            BookingFunction function = reservation.getBookingFunctionId() != null ? functionsById.get(reservation.getBookingFunctionId()) : null;
            Booking booking = function != null ? bookingsById.get(function.getBookingId()) : null;
            if (function != null) {
                functionsWithAllocation.add(function.getId());
            }
            List<Long> rowIds = new ArrayList<>();
            rowIds.add(allocation.getAllocatedResource().getId());
            if (Boolean.TRUE.equals(allocation.getCompositResource()) && allocation.getCompositResourceId() != null) {
                rowIds.add(allocation.getCompositResourceId());
            }
            for (Long rowId : rowIds) {
                if (!seen.add(rowId + ":" + reservation.getId())) {
                    continue;
                }
                bookings.computeIfAbsent(rowId, k -> new ArrayList<>())
                    .add(BookingDtos.SpaceGridBooking.builder()
                            .kind(function != null ? "EVENT" : "RESERVATION")
                            .status(allocation.getStatus())
                            .startsAt(allocation.getStartsAt())
                            .endsAt(allocation.getEndsAt())
                            .units(AllocationCapacityService.unitsOf(allocation))
                            .bookingId(booking != null ? booking.getId() : null)
                            .bookingName(booking != null ? booking.getName() : reservation.getCustomerName())
                            .bookingStatus(booking != null ? booking.getStatus().name() : null)
                            .functionId(function != null ? function.getId() : null)
                            .functionName(function != null ? functionLabel(function) : null)
                            .reservationRequestId(reservation.getRequest() != null ? reservation.getRequest().getId() : null)
                            .build());
            }
        }
        // Functions without a local allocation (typical when space_inventory=OPERA) still belong on the diary.
        for (BookingFunction function : planned) {
            Booking booking = bookingsById.get(function.getBookingId());
            if (booking == null || functionsWithAllocation.contains(function.getId())
                    || function.getResourceId() == null) {
                continue;
            }
            if (booking.getStatus() == Booking.Status.TURNED_DOWN
                    || booking.getStatus() == Booking.Status.LOST
                    || booking.getStatus() == Booking.Status.CANCELLED) {
                continue;
            }
            boolean inquiry = booking.getStatus() == Booking.Status.INQUIRY;
            bookings.computeIfAbsent(function.getResourceId(), k -> new ArrayList<>())
                    .add(BookingDtos.SpaceGridBooking.builder()
                            .kind(inquiry ? "PLANNED" : "EVENT")
                            .status(inquiry ? "INQUIRY" : booking.getStatus().name())
                            .startsAt(function.getOccupancyStartsAt())
                            .endsAt(function.getOccupancyEndsAt())
                            .units(1)
                            .bookingId(booking.getId())
                            .bookingName(booking.getName())
                            .bookingStatus(booking.getStatus().name())
                            .functionId(function.getId())
                            .functionName(functionLabel(function))
                            .build());
        }

        List<BookingDtos.SpaceGridRow> rows = new ArrayList<>();
        for (Resource space : spaces) {
            List<BookingDtos.SpaceGridBooking> list = bookings.getOrDefault(space.getId(), new ArrayList<>());
            list.sort(Comparator.comparing(BookingDtos.SpaceGridBooking::getStartsAt));
            rows.add(BookingDtos.SpaceGridRow.builder()
                    .resourceId(space.getId())
                    .resourceName(space.getName())
                    .resourceType(space.getResourceType().getCode())
                    .totalUnits(AllocationCapacityService.isPool(space) ? space.getPoolTotalUnits() : null)
                    .parentPoolId(space.getParentPool() != null ? space.getParentPool().getId() : null)
                    .bookings(list)
                    .build());
        }
        return rows;
    }

    private Map<Long, String> resourceNames(List<Long> ids) {
        List<Long> nonNull = ids.stream().filter(id -> id != null).distinct().toList();
        if (nonNull.isEmpty()) {
            // A function without a room looks up a null id. Map.of() rejects null keys.
            return new HashMap<>();
        }
        return resourceRepo.findAllById(nonNull).stream().collect(Collectors.toMap(Resource::getId, Resource::getName));
    }

    private static String functionLabel(BookingFunction function) {
        return function.getName() != null ? function.getName() : function.getFunctionType().name();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
