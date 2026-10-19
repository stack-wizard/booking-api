package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.BookingRoomBlockNight;
import com.stackwizard.booking_api.model.BookingStatus;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps the generated reservation lines of a booking in step with the booking status:
 * TENTATIVE holds until the decision date, DEFINITE confirms, everything else releases.
 * <p>
 * Each hotel the booking uses gets one reservation_request ({@code booking_id}, type INTERNAL, no expiry).
 * It is created with the first line in that hotel, removed when its last line goes, and its status follows
 * the booking phase: DRAFT before DEFINITE, FINALIZED from DEFINITE, CANCELLED when the booking is lost.
 * <p>
 * A hotel whose space or room inventory is OPERA gets those lines without a local allocation and its request is
 * marked {@code opera_sync_status = PENDING} for the OPERA block sync.
 */
@Component
public class BookingReservationSync {
    private final ReservationService reservationService;
    private final ReservationRepository reservationRepo;
    private final AllocationRepository allocationRepo;
    private final BookingFunctionRepository functionRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final ReservationRequestRepository requestRepo;
    private final BookingRoomBlockRepository roomBlockRepo;
    private final BookingRoomBlockNightRepository roomNightRepo;
    private final OperaHotelRepository operaHotelRepo;
    private final OperaBlockSyncService operaBlockSync;

    public BookingReservationSync(ReservationService reservationService,
                                ReservationRepository reservationRepo,
                                AllocationRepository allocationRepo,
                                BookingFunctionRepository functionRepo,
                                CrmAccountRepository accountRepo,
                                CrmContactRepository contactRepo,
                                ReservationRequestRepository requestRepo,
                                BookingRoomBlockRepository roomBlockRepo,
                                BookingRoomBlockNightRepository roomNightRepo,
                                OperaHotelRepository operaHotelRepo,
                                @Lazy OperaBlockSyncService operaBlockSync) {
        this.reservationService = reservationService;
        this.reservationRepo = reservationRepo;
        this.allocationRepo = allocationRepo;
        this.functionRepo = functionRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.requestRepo = requestRepo;
        this.roomBlockRepo = roomBlockRepo;
        this.roomNightRepo = roomNightRepo;
        this.operaHotelRepo = operaHotelRepo;
        this.operaBlockSync = operaBlockSync;
    }

    @Transactional
    public void syncBooking(Booking booking) {
        for (BookingFunction function : functionRepo.findForBooking(
                booking.getTenantId(), booking.getId())) {
            syncFunction(booking, function);
        }
        for (BookingRoomBlock block : roomBlockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(
                booking.getTenantId(), booking.getId())) {
            syncRoomBlock(booking, block);
        }
        syncRequests(booking);
        operaBlockSync.syncBooking(booking);
    }

    public boolean hasPlacedRoomBlock(Booking booking) {
        return roomBlockRepo.existsByTenantIdAndBookingIdAndRoomResourceIdIsNotNull(booking.getTenantId(), booking.getId());
    }

    /** Pushes the booking to its OPERA blocks (status, room grid, events). Safe no-op for hotels OPERA does not lead. */
    public void pushOperaBlock(Booking booking) {
        operaBlockSync.syncBooking(booking);
    }

    /** OPERA must accept the move on every block of the booking before it is saved locally. */
    public void verifyOperaTransition(Booking booking, BookingStatus target) {
        operaBlockSync.verifyTransition(booking, target);
    }

    /** Drops the function's OPERA event; call before the function moves hotel, loses its space or is deleted. */
    public void removeOperaEvent(BookingFunction function) {
        operaBlockSync.removeEvent(function);
    }

    /** Request status of every hotel follows the booking phase. */
    @Transactional
    public void syncRequests(Booking booking) {
        for (ReservationRequest request : requestRepo.findByBookingIdOrderByTenantIdAsc(booking.getId())) {
            applyPhase(booking, request);
            requestRepo.save(request);
        }
    }

    /** The booking's request for a hotel, created on first use. */
    @Transactional
    public ReservationRequest requestFor(Booking booking, Long propertyTenantId) {
        ReservationRequest request = requestRepo.findByBookingIdAndTenantId(booking.getId(), propertyTenantId)
                .orElseGet(() -> {
                    CrmAccount account = account(booking);
                    CrmContact contact = contact(booking);
                    return ReservationRequest.builder()
                            .tenantId(propertyTenantId)
                            .bookingId(booking.getId())
                            .type(ReservationRequest.Type.INTERNAL)
                            .status(ReservationRequest.Status.DRAFT)
                            .customerName(account != null ? account.getName() : booking.getName())
                            .customerEmail(email(account, contact))
                            .customerPhone(phone(account, contact))
                            .notes(booking.getName())
                            .extensionCount(0)
                            .build();
                });
        applyPhase(booking, request);
        if (!localSpaceInventory(propertyTenantId) || !localRoomInventory(propertyTenantId)) {
            request.setOperaSyncStatus(ReservationRequest.OperaSyncStatus.PENDING);
        }
        return requestRepo.save(request);
    }

    /** False when an active OPERA hotel of the property keeps function-space inventory. */
    public boolean localSpaceInventory(Long propertyTenantId) {
        return activeOperaHotels(propertyTenantId).stream()
                .noneMatch(h -> h.getSpaceInventory() == OperaHotel.Mode.OPERA);
    }

    /** False when an active OPERA hotel of the property keeps room-block inventory. */
    public boolean localRoomInventory(Long propertyTenantId) {
        return activeOperaHotels(propertyTenantId).stream()
                .noneMatch(h -> h.getRoomInventory() == OperaHotel.Mode.OPERA);
    }

    private List<OperaHotel> activeOperaHotels(Long propertyTenantId) {
        return operaHotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(propertyTenantId).stream()
                .filter(h -> !Boolean.FALSE.equals(h.getActive()))
                .toList();
    }

    /** Removes the booking's request of a hotel once nothing is filed under it any more. */
    @Transactional
    public void dropIfEmpty(Long requestId) {
        if (requestId == null || reservationRepo.existsByRequestId(requestId)) {
            return;
        }
        requestRepo.findById(requestId)
                .filter(r -> r.getBookingId() != null)
                .ifPresent(requestRepo::delete);
    }

    static void applyPhase(Booking booking, ReservationRequest request) {
        Booking.Status status = booking.getStatus();
        request.setExpiresAt(null);
        if (status == Booking.Status.DEFINITE || status == Booking.Status.ACTUAL) {
            request.setStatus(ReservationRequest.Status.FINALIZED);
            if (request.getConfirmationCode() == null) {
                request.setConfirmationCode(UUID.randomUUID().toString());
            }
            if (request.getConfirmedAt() == null) {
                request.setConfirmedAt(OffsetDateTime.now());
            }
        } else if (status.isClosed()) {
            request.setStatus(ReservationRequest.Status.CANCELLED);
        } else {
            request.setStatus(ReservationRequest.Status.DRAFT);
        }
    }

    @Transactional
    public void syncFunction(Booking booking, BookingFunction function) {
        List<Reservation> active = reservationRepo.findActiveByBookingFunctionId(function.getTenantId(), function.getId());
        if (!booking.getStatus().holdsSpace() || function.getResourceId() == null) {
            reservationService.releaseBookingLines(active);
            return;
        }
        OffsetDateTime expiresAt = booking.getStatus() == Booking.Status.TENTATIVE ? holdExpiry(booking) : null;
        Reservation current = active.stream().filter(r -> matches(r, function)).findFirst().orElse(null);
        List<Reservation> stale = active.stream().filter(r -> r != current).toList();
        reservationService.releaseBookingLines(stale);

        if (current == null) {
            ReservationRequest request = requestFor(booking, function.getTenantId());
            Reservation line = reservationService.holdBookingFunctionLine(command(booking, function, request, expiresAt));
            if (booking.getStatus() == Booking.Status.DEFINITE) {
                reservationService.confirmBookingLines(List.of(line));
            }
            return;
        }
        if (booking.getStatus() == Booking.Status.DEFINITE) {
            reservationService.confirmBookingLines(List.of(current));
        } else {
            reservationService.extendBookingLineHolds(List.of(current), expiresAt);
        }
    }

    /**
     * One line per night with {@code units} = the night's quota. A changed quota resizes the same line,
     * quota 0 or a night outside the block releases it.
     */
    @Transactional
    public void syncRoomBlock(Booking booking, BookingRoomBlock block) {
        if (block.getPropertyTenantId() == null) {
            return;
        }
        List<Reservation> active = reservationRepo
                .findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(block.getPropertyTenantId(), block.getId()).stream()
                .filter(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()))
                .toList();
        if (!booking.getStatus().holdsSpace() || block.getRoomResourceId() == null) {
            reservationService.releaseBookingLines(active);
            return;
        }
        Map<LocalDate, Integer> quota = quota(block);
        Map<LocalDate, Reservation> byNight = new HashMap<>();
        List<Reservation> stale = new ArrayList<>();
        for (Reservation line : active) {
            LocalDate night = line.getStartsAt().toLocalDate();
            boolean wanted = quota.getOrDefault(night, 0) > 0
                    && line.getRequestedResource() != null
                    && Objects.equals(line.getRequestedResource().getId(), block.getRoomResourceId())
                    && !byNight.containsKey(night);
            if (wanted) {
                byNight.put(night, line);
            } else {
                stale.add(line);
            }
        }
        reservationService.releaseBookingLines(stale);

        OffsetDateTime expiresAt = booking.getStatus() == Booking.Status.TENTATIVE ? holdExpiry(booking) : null;
        boolean definite = booking.getStatus() == Booking.Status.DEFINITE;
        ReservationRequest request = null;
        List<Reservation> kept = new ArrayList<>();
        List<Reservation> created = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> entry : quota.entrySet()) {
            int qty = entry.getValue();
            if (qty <= 0) {
                continue;
            }
            Reservation line = byNight.get(entry.getKey());
            if (line != null) {
                if (!Objects.equals(line.getUnits(), qty)) {
                    line = reservationService.resizeBookingLine(line, qty);
                }
                kept.add(line);
                continue;
            }
            if (request == null) {
                request = requestFor(booking, block.getPropertyTenantId());
            }
            created.add(reservationService.holdBookingRoomNight(roomNight(booking, block, request, entry.getKey(), qty, expiresAt)));
        }
        if (definite) {
            reservationService.confirmBookingLines(kept);
            reservationService.confirmBookingLines(created);
        } else {
            reservationService.extendBookingLineHolds(kept, expiresAt);
        }
        if (request != null && !Objects.equals(block.getRequestId(), request.getId())) {
            block.setRequestId(request.getId());
            roomBlockRepo.save(block);
        }
    }

    /** Releases and physically removes the block's lines, e.g. before it moves to another hotel or room type. */
    @Transactional
    public void detachRoomBlock(BookingRoomBlock block) {
        if (block.getPropertyTenantId() == null) {
            return;
        }
        removeLines(reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(
                block.getPropertyTenantId(), block.getId()));
        block.setRequestId(null);
    }

    /** Quota per night of the block's stay; nights without a row count as 0. */
    Map<LocalDate, Integer> quota(BookingRoomBlock block) {
        Map<LocalDate, Integer> quota = new java.util.LinkedHashMap<>();
        for (LocalDate night = block.getDateFrom(); night.isBefore(block.getDateTo()); night = night.plusDays(1)) {
            quota.put(night, 0);
        }
        for (BookingRoomBlockNight row : roomNightRepo.findByTenantIdAndBlockIdOrderByNightAsc(block.getTenantId(), block.getId())) {
            if (quota.containsKey(row.getNight())) {
                quota.put(row.getNight(), row.getQty());
            }
        }
        return quota;
    }

    static final LocalTime CHECK_IN = LocalTime.of(15, 0);
    static final LocalTime CHECK_OUT = LocalTime.of(11, 0);

    private ReservationService.RoomNightCommand roomNight(Booking booking, BookingRoomBlock block, ReservationRequest request,
                                                          LocalDate night, int units, OffsetDateTime expiresAt) {
        CrmAccount account = account(booking);
        CrmContact contact = contact(booking);
        return new ReservationService.RoomNightCommand(
                block.getPropertyTenantId(),
                request,
                block.getId(),
                block.getRoomResourceId(),
                LocalDateTime.of(night, CHECK_IN),
                LocalDateTime.of(night.plusDays(1), CHECK_OUT),
                units,
                booking.getCurrency(),
                account != null ? account.getName() : booking.getName(),
                email(account, contact),
                phone(account, contact),
                expiresAt,
                localRoomInventory(block.getPropertyTenantId()));
    }

    /**
     * Releases and physically removes the function's lines so the function row can be deleted.
     */
    @Transactional
    public void detachFunction(Booking booking, BookingFunction function) {
        removeLines(reservationRepo.findByTenantIdAndBookingFunctionIdIn(
                function.getTenantId(), List.of(function.getId())));
    }

    private void removeLines(List<Reservation> lines) {
        if (lines.isEmpty()) {
            return;
        }
        reservationService.releaseBookingLines(lines);
        allocationRepo.deleteByReservationIdIn(lines.stream().map(Reservation::getId).toList());
        reservationRepo.deleteAll(lines);
        reservationRepo.flush();
        lines.stream()
                .map(r -> r.getRequest() != null ? r.getRequest().getId() : null)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(this::dropIfEmpty);
    }

    /** Active lines of the functions; every line belongs to the hotel that hosts its function. */
    public List<Reservation> activeLines(List<BookingFunction> functions) {
        List<Reservation> lines = new java.util.ArrayList<>();
        functions.stream()
                .collect(java.util.stream.Collectors.groupingBy(BookingFunction::getTenantId,
                        java.util.stream.Collectors.mapping(BookingFunction::getId, java.util.stream.Collectors.toList())))
                .forEach((tenantId, ids) -> lines.addAll(reservationRepo.findByTenantIdAndBookingFunctionIdIn(tenantId, ids)));
        return lines.stream()
                .filter(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()))
                .toList();
    }

    static OffsetDateTime holdExpiry(Booking booking) {
        if (booking.getDecisionDate() == null) {
            throw new IllegalStateException("decisionDate is required for a tentative hold");
        }
        return booking.getDecisionDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static boolean matches(Reservation line, BookingFunction function) {
        return line.getRequestedResource() != null
                && Objects.equals(line.getRequestedResource().getId(), function.getResourceId())
                && Objects.equals(line.getStartsAt(), function.getOccupancyStartsAt())
                && Objects.equals(line.getEndsAt(), function.getOccupancyEndsAt());
    }

    private ReservationService.BookingLineCommand command(Booking booking, BookingFunction function,
                                                          ReservationRequest request, OffsetDateTime expiresAt) {
        CrmAccount account = account(booking);
        CrmContact contact = contact(booking);
        return new ReservationService.BookingLineCommand(
                function.getTenantId(),
                request,
                function.getId(),
                function.getResourceId(),
                function.getOccupancyStartsAt(),
                function.getOccupancyEndsAt(),
                booking.getCurrency(),
                account != null ? account.getName() : booking.getName(),
                email(account, contact),
                phone(account, contact),
                expiresAt,
                function.getPackageProductId() != null,
                localSpaceInventory(function.getTenantId()));
    }

    private CrmAccount account(Booking booking) {
        return booking.getAccountId() == null ? null
                : accountRepo.findByIdAndTenantId(booking.getAccountId(), booking.getTenantId()).orElse(null);
    }

    private CrmContact contact(Booking booking) {
        return booking.getPrimaryContactId() == null ? null
                : contactRepo.findByIdAndTenantId(booking.getPrimaryContactId(), booking.getTenantId()).orElse(null);
    }

    private static String email(CrmAccount account, CrmContact contact) {
        return contact != null && contact.getEmail() != null ? contact.getEmail() : account != null ? account.getEmail() : null;
    }

    private static String phone(CrmAccount account, CrmContact contact) {
        return contact != null && contact.getPhone() != null ? contact.getPhone() : account != null ? account.getPhone() : null;
    }
}
