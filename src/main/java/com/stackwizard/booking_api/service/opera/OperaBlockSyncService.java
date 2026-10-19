package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingOperaBlock;
import com.stackwizard.booking_api.model.BookingRoomBlock;
import com.stackwizard.booking_api.model.BookingRoomBlockNight;
import com.stackwizard.booking_api.model.BookingStatus;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaCodeMap;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingOperaBlockRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockNightRepository;
import com.stackwizard.booking_api.repository.BookingRoomBlockRepository;
import com.stackwizard.booking_api.repository.BookingStatusRepository;
import com.stackwizard.booking_api.repository.OperaCodeMapRepository;
import com.stackwizard.booking_api.repository.OperaCodeRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps one OPERA block per hotel of a booking for hotels whose rooms or function spaces OPERA keeps.
 * <p>
 * The block is created with the first room type or space placed in the hotel, in the OPERA starting status nearest to
 * the booking's status, and then follows every status move along the OPERA status flow. Placed room blocks become the
 * room grid ({@code allocationRange}), functions in OPERA-kept spaces become OPERA events on the block.
 */
@Service
public class OperaBlockSyncService {
    private static final Logger log = LoggerFactory.getLogger(OperaBlockSyncService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXTERNAL_CONTEXT = "MIKOS";
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.0");

    private final OperaHotelRepository hotelRepo;
    private final BookingOperaBlockRepository blockRepo;
    private final BookingRoomBlockRepository roomBlockRepo;
    private final BookingRoomBlockNightRepository roomNightRepo;
    private final BookingFunctionRepository functionRepo;
    private final ReservationRepository reservationRepo;
    private final ReservationRequestRepository requestRepo;
    private final ResourceRepository resourceRepo;
    private final OperaCodeMapRepository mapRepo;
    private final OperaCodeRepository codeRepo;
    private final BookingStatusRepository statusRepo;
    private final OperaPostingClient client;
    private final OperaTenantConfigResolver configResolver;

    public OperaBlockSyncService(OperaHotelRepository hotelRepo,
                                 BookingOperaBlockRepository blockRepo,
                                 BookingRoomBlockRepository roomBlockRepo,
                                 BookingRoomBlockNightRepository roomNightRepo,
                                 BookingFunctionRepository functionRepo,
                                 ReservationRepository reservationRepo,
                                 ReservationRequestRepository requestRepo,
                                 ResourceRepository resourceRepo,
                                 OperaCodeMapRepository mapRepo,
                                 OperaCodeRepository codeRepo,
                                 BookingStatusRepository statusRepo,
                                 OperaPostingClient client,
                                 OperaTenantConfigResolver configResolver) {
        this.hotelRepo = hotelRepo;
        this.blockRepo = blockRepo;
        this.roomBlockRepo = roomBlockRepo;
        this.roomNightRepo = roomNightRepo;
        this.functionRepo = functionRepo;
        this.reservationRepo = reservationRepo;
        this.requestRepo = requestRepo;
        this.resourceRepo = resourceRepo;
        this.mapRepo = mapRepo;
        this.codeRepo = codeRepo;
        this.statusRepo = statusRepo;
        this.client = client;
        this.configResolver = configResolver;
    }

    @Transactional
    public void syncBooking(Booking booking) {
        List<BookingRoomBlock> rooms = roomBlockRepo.findByTenantIdAndBookingIdOrderByDateFromAscIdAsc(
                booking.getTenantId(), booking.getId());
        List<BookingFunction> functions = functionRepo.findForBooking(booking.getTenantId(), booking.getId());
        Set<Long> properties = new LinkedHashSet<>();
        rooms.stream().filter(b -> b.getRoomResourceId() != null).map(BookingRoomBlock::getPropertyTenantId)
                .filter(Objects::nonNull).forEach(properties::add);
        functions.stream().filter(f -> f.getResourceId() != null || StringUtils.hasText(f.getOperaEventId()))
                .map(BookingFunction::getTenantId).forEach(properties::add);
        blockRepo.findByTenantIdAndBookingIdOrderByIdAsc(booking.getTenantId(), booking.getId())
                .forEach(row -> properties.add(row.getPropertyTenantId()));
        for (Long property : properties) {
            syncHotel(booking, property, rooms, functions);
        }
    }

    /**
     * Asks OPERA whether every block of the booking can take the first step towards {@code target}; a refusal stops
     * the local move.
     */
    public void verifyTransition(Booking booking, BookingStatus target) {
        for (BookingOperaBlock row : blockRepo.findByTenantIdAndBookingIdOrderByIdAsc(booking.getTenantId(), booking.getId())) {
            if (!StringUtils.hasText(row.getBlockId())) {
                continue;
            }
            OperaHotel hotel = hotelRepo.findById(row.getOperaHotelId()).orElse(null);
            if (hotel == null) {
                continue;
            }
            OperaStatusGraph graph = graph(hotel);
            String to = operaCode(target, graph);
            if (to.equalsIgnoreCase(row.getStatusCode())) {
                continue;
            }
            List<String> path = graph.path(row.getStatusCode(), to);
            if (path.isEmpty()) {
                throw new IllegalStateException("OPERA block " + row.getBlockCode() + " (" + hotel.getHotelCode()
                        + ") cannot move from " + row.getStatusCode() + " to " + to);
            }
            try {
                changeStatus(configResolver.resolve(hotel.getTenantId()), hotel, row, graph, path.get(0), true);
            } catch (RuntimeException ex) {
                throw new IllegalStateException("OPERA refused " + row.getStatusCode() + " → " + path.get(0)
                        + " on block " + row.getBlockCode() + ": " + ex.getMessage(), ex);
            }
        }
    }

    /** Removes the OPERA event of a function before it moves to another hotel, loses its space or is deleted. */
    public void removeEvent(BookingFunction function) {
        if (!StringUtils.hasText(function.getOperaEventId())) {
            return;
        }
        Optional<OperaHotel> hotel = activeHotels(function.getTenantId()).stream().findFirst();
        if (hotel.isPresent()) {
            try {
                client.delete(configResolver.resolve(hotel.get().getTenantId()), hotel.get().getChainCode(),
                        hotel.get().getHotelCode(),
                        "/evm/v1/hotels/" + hotel.get().getHotelCode() + "/events/" + function.getOperaEventId());
            } catch (RuntimeException ex) {
                if (!String.valueOf(ex.getMessage()).contains(" 404")) {
                    throw new IllegalStateException("OPERA event " + function.getOperaEventId()
                            + " could not be removed: " + ex.getMessage(), ex);
                }
            }
        }
        function.setOperaEventId(null);
    }

    private void syncHotel(Booking booking, Long propertyTenantId, List<BookingRoomBlock> allRooms,
                           List<BookingFunction> allFunctions) {
        Optional<OperaHotel> hotelOpt = activeHotels(propertyTenantId).stream()
                .filter(h -> h.getRoomInventory() == OperaHotel.Mode.OPERA || h.getSpaceInventory() == OperaHotel.Mode.OPERA)
                .findFirst();
        if (hotelOpt.isEmpty()) {
            return;
        }
        OperaHotel hotel = hotelOpt.get();
        List<BookingRoomBlock> rooms = hotel.getRoomInventory() != OperaHotel.Mode.OPERA ? List.of() : allRooms.stream()
                .filter(b -> propertyTenantId.equals(b.getPropertyTenantId()) && b.getRoomResourceId() != null)
                .toList();
        List<BookingFunction> hotelFunctions = hotel.getSpaceInventory() != OperaHotel.Mode.OPERA ? List.of()
                : allFunctions.stream().filter(f -> propertyTenantId.equals(f.getTenantId())).toList();
        List<BookingFunction> events = hotelFunctions.stream().filter(f -> f.getResourceId() != null).toList();
        boolean closed = booking.getStatus().isClosed();
        Optional<BookingOperaBlock> existing = blockRepo.findByBookingIdAndOperaHotelId(booking.getId(), hotel.getId());
        if (existing.isEmpty() && (closed || rooms.isEmpty() && events.isEmpty())) {
            return;
        }
        BookingOperaBlock row = existing.orElseGet(() -> BookingOperaBlock.builder()
                .tenantId(booking.getTenantId())
                .bookingId(booking.getId())
                .operaHotelId(hotel.getId())
                .propertyTenantId(propertyTenantId)
                .syncStatus(BookingOperaBlock.SyncStatus.PENDING)
                .build());
        requestRepo.findByBookingIdAndTenantId(booking.getId(), propertyTenantId)
                .ifPresent(r -> row.setRequestId(r.getId()));
        try {
            OperaTenantConfigResolver.OperaResolvedConfig config = configResolver.resolve(hotel.getTenantId());
            OperaStatusGraph graph = graph(hotel);
            String target = operaCode(currentStatus(booking), booking.getStatus(), graph);
            if (!StringUtils.hasText(row.getBlockId())) {
                createBlock(booking, hotel, row, rooms, events, graph.startingFor(target), graph, config);
            } else if (!closed) {
                extendDates(booking, hotel, row, rooms, events, config);
            }
            moveStatus(config, hotel, row, graph, target);
            List<String> eventErrors = new ArrayList<>();
            if (!closed) {
                if (!rooms.isEmpty() && booking.getStatus() != Booking.Status.INQUIRY) {
                    pushGrid(hotel, row, rooms, graph, config);
                }
                for (BookingFunction function : hotelFunctions) {
                    if (function.getResourceId() == null) {
                        removeEvent(function);
                        functionRepo.save(function);
                    } else {
                        try {
                            pushEvent(hotel, row, function, config);
                        } catch (RuntimeException ex) {
                            // Block + status already moved; keep SYNCED and surface the event problem.
                            log.warn("OPERA event sync failed for booking {} function {}: {}",
                                    booking.getId(), function.getName(), ex.getMessage());
                            eventErrors.add(trim(function.getName() + ": " + ex.getMessage(), 500));
                        }
                    }
                }
            }
            row.setSyncStatus(BookingOperaBlock.SyncStatus.SYNCED);
            row.setLastError(eventErrors.isEmpty() ? null : trim(String.join(" | ", eventErrors), 2000));
            row.setSyncedAt(OffsetDateTime.now());
            blockRepo.save(row);
            markRequest(row, ReservationRequest.OperaSyncStatus.SYNCED);
        } catch (RuntimeException ex) {
            log.warn("OPERA block sync failed for booking {} hotel {}: {}", booking.getId(), hotel.getHotelCode(),
                    ex.getMessage());
            row.setSyncStatus(BookingOperaBlock.SyncStatus.FAILED);
            row.setLastError(trim(ex.getMessage(), 2000));
            blockRepo.save(row);
            markRequest(row, ReservationRequest.OperaSyncStatus.FAILED);
        }
    }

    private void markRequest(BookingOperaBlock row, ReservationRequest.OperaSyncStatus status) {
        if (row.getRequestId() != null) {
            requestRepo.findById(row.getRequestId()).ifPresent(r -> {
                r.setOperaSyncStatus(status);
                requestRepo.save(r);
            });
        }
    }

    private void createBlock(Booking booking, OperaHotel hotel, BookingOperaBlock row, List<BookingRoomBlock> rooms,
                             List<BookingFunction> events, String statusCode, OperaStatusGraph graph,
                             OperaTenantConfigResolver.OperaResolvedConfig config) {
        LocalDate[] span = span(booking, rooms, events);
        String reservationType = reservationType(hotel, graph.deducts(statusCode));
        String market = requireCode(hotel.getDefaultMarketCode(), hotel, OperaCode.Kind.MARKET, "market");
        String source = requireCode(hotel.getDefaultSourceCode(), hotel, OperaCode.Kind.SOURCE, "source");
        String blockCode = blockCode(booking);
        boolean hasRooms = rooms.stream().anyMatch(r -> r.getRoomResourceId() != null);
        String ratePlan = hasRooms ? resolveRatePlanCode(hotel, rooms) : null;
        String owner = StringUtils.hasText(hotel.getDefaultBlockOwnerCode())
                ? hotel.getDefaultBlockOwnerCode().trim() : "ALL";

        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode block = body.putObject("blocks").putArray("blockInfo").addObject().putObject("block");
        ObjectNode details = block.putObject("blockDetails");
        details.put("blockCode", blockCode);
        details.put("blockName", trim(booking.getName(), 200));
        ObjectNode timeSpan = details.putObject("timeSpan");
        timeSpan.put("startDate", span[0].toString());
        timeSpan.put("endDate", span[1].toString());
        details.putObject("blockStatus").putObject("bookingStatus").putObject("status").put("code", statusCode);
        details.putObject("reservationType").put("reservationType", reservationType);
        details.putObject("marketCode").put("marketCode", market);
        details.putObject("sourceOfSale").putObject("sourceCode").put("sourceCode", source);
        details.put("currencyCode", booking.getCurrency() != null ? booking.getCurrency() : "EUR");
        details.put("blockClassification", "RegularBooking");
        details.put("cateringOnlyBlock", rooms.isEmpty() && hotel.getRoomInventory() != OperaHotel.Mode.OPERA);
        details.put("allowRateOverride", true);
        details.put("controlBlockLocally", false);

        ObjectNode blockOwners = block.putObject("blockOwners");
        ArrayNode owners = blockOwners.putArray("owner");
        for (String ownership : List.of("Block", "Rooms", "Catering")) {
            ObjectNode o = owners.addObject();
            o.put("ownership", ownership);
            o.put("ownerCode", owner);
            o.put("primary", true);
        }
        blockOwners.put("lockBlockOwners", false);
        blockOwners.put("lockRoomsOwners", false);
        blockOwners.put("lockCateringOwners", false);

        if (hasRooms || ratePlan != null || booking.getDecisionDate() != null) {
            ObjectNode reservationDetails = block.putObject("reservationDetails");
            if (ratePlan != null) {
                ObjectNode rate = reservationDetails.putArray("ratePlanCode").addObject();
                rate.put("primary", true);
                rate.put("ratePlanCode", ratePlan);
            }
            if (hasRooms) {
                reservationDetails.put("inventoryControl", "Elastic");
                reservationDetails.put("elastic", 1);
            }
            if (booking.getDecisionDate() != null) {
                reservationDetails.put("decisionDate", booking.getDecisionDate().toString());
                reservationDetails.put("followupDate", booking.getDecisionDate().toString());
            }
        }

        ObjectNode ext = block.putArray("externalReferences").addObject();
        ext.put("id", String.valueOf(booking.getId()));
        ext.put("idContext", EXTERNAL_CONTEXT);

        row.setLastRequest(body);
        OperaPostingClient.WriteResult result = client.post(config, hotel.getChainCode(), hotel.getHotelCode(),
                "/blk/v1/hotels/" + hotel.getHotelCode() + "/block", body);
        row.setLastResponse(result.body());
        String blockId = extractBlockId(result.location(), result.body());
        if (!StringUtils.hasText(blockId)) {
            throw new IllegalStateException("OPERA did not return a block id for " + blockCode);
        }
        row.setBlockId(blockId);
        row.setBlockCode(blockCode);
        row.setStatusCode(statusCode);
        row.setDateFrom(span[0]);
        row.setDateTo(span[1]);
        blockRepo.save(row);
    }

    /**
     * Room rate plan for the OPERA block header from {@code price_profile} ↔ {@code RATE_CODE} map (per hotel).
     */
    String resolveRatePlanCode(OperaHotel hotel, List<BookingRoomBlock> rooms) {
        Long profileId = rooms.stream()
                .filter(r -> r.getRoomResourceId() != null && r.getRatePriceProfileId() != null)
                .map(BookingRoomBlock::getRatePriceProfileId)
                .findFirst()
                .orElseGet(() -> rooms.stream()
                        .filter(r -> r.getRoomResourceId() != null)
                        .map(BookingRoomBlock::getRatePriceProfileId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null));
        if (profileId == null) {
            throw new IllegalStateException("Set rate price profile on a placed room block before syncing OPERA rooms for "
                    + hotel.getHotelCode());
        }
        return mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(
                        hotel.getTenantId(), hotel.getId(), OperaCodeMap.Kind.RATE_CODE, profileId.toString())
                .map(OperaCodeMap::getOperaCode)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .orElseThrow(() -> new IllegalStateException(
                        "Map price profile " + profileId + " to an OPERA RATE_CODE for hotel " + hotel.getHotelCode()));
    }

    /** Widens the OPERA block dates when a room night or function falls outside them; never shrinks. */
    private void extendDates(Booking booking, OperaHotel hotel, BookingOperaBlock row, List<BookingRoomBlock> rooms,
                             List<BookingFunction> events, OperaTenantConfigResolver.OperaResolvedConfig config) {
        LocalDate[] span = span(booking, rooms, events);
        LocalDate from = row.getDateFrom() == null || span[0].isBefore(row.getDateFrom()) ? span[0] : row.getDateFrom();
        LocalDate to = row.getDateTo() == null || span[1].isAfter(row.getDateTo()) ? span[1] : row.getDateTo();
        if (from.equals(row.getDateFrom()) && to.equals(row.getDateTo())) {
            return;
        }
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode block = body.putArray("blocks").addObject();
        block.putArray("blockIdList").add(blockId(row));
        block.put("hotelId", hotel.getHotelCode());
        ObjectNode timeSpan = block.putObject("blockDetails").putObject("timeSpan");
        timeSpan.put("startDate", from.toString());
        timeSpan.put("endDate", to.toString());
        row.setLastRequest(body);
        row.setLastResponse(client.put(config, hotel.getChainCode(), hotel.getHotelCode(),
                "/blk/v1/hotels/" + hotel.getHotelCode() + "/blocks/" + row.getBlockId(), body));
        row.setDateFrom(from);
        row.setDateTo(to);
        blockRepo.save(row);
    }

    /** Arrival to departure of the block: the booking dates widened by every placed room night and function. */
    static LocalDate[] span(Booking booking, List<BookingRoomBlock> rooms, List<BookingFunction> events) {
        List<LocalDate> starts = new ArrayList<>();
        List<LocalDate> ends = new ArrayList<>();
        if (booking.getDateFrom() != null) {
            starts.add(booking.getDateFrom());
        }
        if (booking.getDateTo() != null) {
            ends.add(booking.getDateTo());
        }
        rooms.forEach(r -> {
            starts.add(r.getDateFrom());
            ends.add(r.getDateTo());
        });
        events.forEach(f -> {
            starts.add(f.getStartsAt().toLocalDate());
            ends.add(f.getEndsAt().toLocalDate());
        });
        LocalDate start = starts.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate end = ends.stream().max(LocalDate::compareTo).orElse(start);
        if (!end.isAfter(start)) {
            end = start.plusDays(1);
        }
        return new LocalDate[]{start, end};
    }

    private void moveStatus(OperaTenantConfigResolver.OperaResolvedConfig config, OperaHotel hotel,
                            BookingOperaBlock row, OperaStatusGraph graph, String target) {
        if (target.equalsIgnoreCase(row.getStatusCode())) {
            return;
        }
        List<String> path = graph.path(row.getStatusCode(), target);
        if (path.isEmpty()) {
            throw new IllegalStateException("OPERA has no status flow from " + row.getStatusCode() + " to " + target);
        }
        for (String step : path) {
            changeStatus(config, hotel, row, graph, step, false);
            row.setStatusCode(step);
            blockRepo.save(row);
        }
    }

    private void changeStatus(OperaTenantConfigResolver.OperaResolvedConfig config, OperaHotel hotel,
                              BookingOperaBlock row, OperaStatusGraph graph, String next, boolean verificationOnly) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("verificationOnly", verificationOnly);
        ObjectNode change = body.putObject("changeBlockStatus");
        change.put("hotelId", hotel.getHotelCode());
        change.set("blockId", blockId(row));
        change.put("currentBlockStatus", row.getStatusCode());
        change.put("newBlockStatus", next);
        change.put("reservationType", reservationType(hotel, graph.deducts(next)));
        change.put("overbookAll", false);
        boolean release = graph.returnsToInventory(next);
        change.put("cancelAllPMReservations", release);
        if (release) {
            change.putObject("cancellationDetails").put("cancellationInfo", "Booking " + row.getBookingId() + " " + next);
        }
        // Simple Events has no separate catering status on the block; forcing this true yields BLK01136.
        change.put("applyChangesToCateringSatus", false);
        change.put("overrideEventsProcessingWarnings", true);
        if (!verificationOnly) {
            row.setLastRequest(body);
        }
        JsonNode response = client.put(config, hotel.getChainCode(), hotel.getHotelCode(),
                "/blk/v1/hotels/" + hotel.getHotelCode() + "/blocks/" + row.getBlockId() + "/status", body);
        if (!verificationOnly) {
            row.setLastResponse(response);
        }
    }

    /** OPERA rejects grid rates when the block has a rate code unless override is enabled (BLK00063 / BLK01158). */
    private void enableRateOverride(OperaHotel hotel, BookingOperaBlock row,
                                    OperaTenantConfigResolver.OperaResolvedConfig config) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("hotelId", hotel.getHotelCode());
        body.putArray("blockIdList").add(blockId(row));
        body.put("allowRateOverride", true);
        ObjectNode reason = body.putObject("rateOverrideReason");
        reason.put("code", "VIP");
        reason.put("description", "Negotiated MICE rate");
        row.setLastRequest(body);
        row.setLastResponse(client.put(config, hotel.getChainCode(), hotel.getHotelCode(),
                "/blk/v1/hotels/" + hotel.getHotelCode() + "/blocks/" + row.getBlockId() + "/rate/override",
                body));
    }

    private void pushGrid(OperaHotel hotel, BookingOperaBlock row, List<BookingRoomBlock> placed, OperaStatusGraph graph,
                          OperaTenantConfigResolver.OperaResolvedConfig config) {
        // Our room amounts always go as blockRates; with a rate plan Opera requires override first (BLK00063).
        enableRateOverride(hotel, row, config);
        String allocationType = graph.deducts(row.getStatusCode()) ? "Actual" : "Initial";
        for (BookingRoomBlock block : placed) {
            String roomType = operaRoomType(hotel, block.getRoomResourceId());
            for (BookingRoomBlockNight night : roomNightRepo.findByTenantIdAndBlockIdOrderByNightAsc(block.getTenantId(), block.getId())) {
                if (night.getQty() == null || night.getQty() < 0) {
                    continue;
                }
                BigDecimal rate = rateOf(block, night.getNight());
                ObjectNode body = MAPPER.createObjectNode();
                ObjectNode range = body.putObject("blockAllocationRange");
                range.set("blockId", blockId(row));
                range.put("hotelId", hotel.getHotelCode());
                range.putArray("roomTypes").add(roomType);
                range.put("beginDate", night.getNight().toString());
                range.put("endDate", night.getNight().toString());
                range.put("allocationType", allocationType);
                range.put("incrementFlag", false);
                range.putObject("blockInventory").put("onePerson", String.valueOf(night.getQty()));
                if (rate != null) {
                    range.putObject("blockRates").put("onePerson", rate.toPlainString());
                }
                range.put("includedDays", "1111111");
                range.put("rangeMode", "Core");
                range.put("genericRoomType", false);
                row.setLastRequest(body);
                row.setLastResponse(client.put(config, hotel.getChainCode(), hotel.getHotelCode(),
                        "/blk/v1/hotels/" + hotel.getHotelCode() + "/blocks/" + row.getBlockId() + "/allocationRange",
                        body));
            }
        }
    }

    private void pushEvent(OperaHotel hotel, BookingOperaBlock row, BookingFunction function,
                           OperaTenantConfigResolver.OperaResolvedConfig config) {
        boolean create = !StringUtils.hasText(function.getOperaEventId());
        try {
            writeEvent(hotel, row, function, config, create, true);
        } catch (IllegalStateException ex) {
            if (create && isInvalidSetup(ex) && StringUtils.hasText(function.getSetupStyle())) {
                log.warn("OPERA rejected setup {} for function {}; retrying without setup code",
                        function.getSetupStyle(), function.getName());
                writeEvent(hotel, row, function, config, true, false);
                return;
            }
            throw ex;
        }
    }

    private void writeEvent(OperaHotel hotel, BookingOperaBlock row, BookingFunction function,
                            OperaTenantConfigResolver.OperaResolvedConfig config, boolean create, boolean withSetup) {
        ObjectNode body = eventBody(hotel, row, function, create ? "-1" : function.getOperaEventId());
        if (!withSetup) {
            JsonNode space = body.at("/eventDetails/0/functionSpaceInformation");
            if (space instanceof ObjectNode spaceObj) {
                spaceObj.remove("setupCode");
            }
        }
        row.setLastRequest(body);
        if (create) {
            OperaPostingClient.WriteResult result = client.post(config, hotel.getChainCode(), hotel.getHotelCode(),
                    "/evm/v1/events", body);
            row.setLastResponse(result.body());
            String eventId = extractEventId(result.location(), result.body());
            if (!StringUtils.hasText(eventId)) {
                throw new IllegalStateException("OPERA did not return an event id for function " + function.getName());
            }
            function.setOperaEventId(eventId);
            functionRepo.save(function);
        } else {
            row.setLastResponse(client.put(config, hotel.getChainCode(), hotel.getHotelCode(),
                    "/evm/v1/hotels/" + hotel.getHotelCode() + "/events/" + function.getOperaEventId(), body));
        }
    }

    private static boolean isInvalidSetup(IllegalStateException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "";
        return msg.contains("EVM01032") || msg.toLowerCase(Locale.ROOT).contains("invalid setup");
    }

    ObjectNode eventBody(OperaHotel hotel, BookingOperaBlock row, BookingFunction function, String eventId) {
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode event = body.putArray("eventDetails").addObject();
        ObjectNode primary = event.putObject("eventPrimaryInfo");
        ObjectNode id = primary.putObject("eventId");
        id.put("type", "EventId");
        id.put("idContext", "OPERA");
        id.put("id", eventId);
        primary.put("hotelId", hotel.getHotelCode());

        ObjectNode detail = event.putObject("eventDetail");
        String name = StringUtils.hasText(function.getName()) ? function.getName() : function.getFunctionType().name();
        detail.putObject("eventName").put("defaultText", trim(name, 80));
        detail.put("eventType", operaEventType(hotel, function));
        ObjectNode timeSpan = detail.putObject("eventTimeSpan");
        timeSpan.put("startDateTime", function.getStartsAt().format(EVENT_TIME));
        timeSpan.put("endDateTime", function.getEndsAt().format(EVENT_TIME));
        detail.putObject("eventStatus").putObject("status").put("code", row.getStatusCode());
        if (function.getPax() != null) {
            detail.putObject("attendees").put("expected", String.valueOf(function.getPax()));
        }
        detail.put("doorcard", trim(name, 80));
        detail.put("notMoveable", false);
        detail.put("displayDoorcard", true);

        ObjectNode instructions = event.putObject("processInstructions");
        instructions.put("overrideCapacity", false);
        instructions.put("overbook", false);
        instructions.put("overrideMaxgroups", false);
        instructions.put("warnFunctionSpace", true);

        ObjectNode space = event.putObject("functionSpaceInformation");
        space.put("functionSpaceCode", operaFunctionSpace(hotel, function.getResourceId()));
        String setup = operaSetupStyle(hotel, function.getSetupStyle());
        if (setup != null) {
            space.put("setupCode", setup);
        }
        // Function-space rental codes (Daily/Hourly/CUSTOM), not room rate plans — room RATE_CODE yields EVM01033.
        // CUSTOM + amount is what OPERA accepts when we price the hold ourselves; catering goes to the PM separately.
        String rentalCode = StringUtils.hasText(hotel.getDefaultRentalCode()) ? hotel.getDefaultRentalCode().trim() : "CUSTOM";
        space.put("rentalCode", rentalCode);
        ObjectNode amount = space.putObject("rentalAmount");
        amount.put("amount", 0);
        amount.put("currencyCode", "EUR");
        space.put("setupTime", String.valueOf(minutes(function.getOccupancyStartsAt(), function.getStartsAt())));
        space.put("setdownTime", String.valueOf(minutes(function.getEndsAt(), function.getOccupancyEndsAt())));

        ObjectNode blockInfo = event.putObject("eventBlockInfo");
        ObjectNode blockRef = blockInfo.putArray("blockIdList").addObject();
        blockRef.put("type", "Block");
        blockRef.put("id", row.getBlockId());
        blockInfo.put("hotelId", hotel.getHotelCode());
        return body;
    }

    private static long minutes(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return 0;
        }
        return Duration.between(from, to).toMinutes();
    }

    private String operaEventType(OperaHotel hotel, BookingFunction function) {
        String local = function.getEventType();
        if (!StringUtils.hasText(local)) {
            throw new IllegalStateException("Choose the OPERA event type of function \"" + function.getName() + "\"");
        }
        return mapped(hotel, OperaCodeMap.Kind.EVENT_TYPE, local)
                .orElseThrow(() -> new IllegalStateException("Event type " + local
                        + " is not an OPERA event type of " + hotel.getHotelCode() + "; run Setup from OPERA"));
    }

    private String operaSetupStyle(OperaHotel hotel, String local) {
        if (!StringUtils.hasText(local)) {
            return null;
        }
        return mapped(hotel, OperaCodeMap.Kind.SETUP_STYLE, local).orElse(null);
    }

    private String operaFunctionSpace(OperaHotel hotel, Long resourceId) {
        Optional<String> byMap = mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(
                        hotel.getTenantId(), hotel.getId(), OperaCodeMap.Kind.FUNCTION_SPACE, resourceId.toString())
                .map(OperaCodeMap::getOperaCode)
                .filter(StringUtils::hasText);
        if (byMap.isPresent()) {
            return byMap.get();
        }
        String code = resourceRepo.findById(resourceId).map(r -> r.getCode()).orElse(null);
        return Optional.ofNullable(code)
                .flatMap(c -> codeRepo.findByTenantIdAndOperaHotelIdAndKindAndCode(
                        hotel.getTenantId(), hotel.getId(), OperaCode.Kind.FUNCTION_SPACE, c))
                .map(OperaCode::getCode)
                .orElseThrow(() -> new IllegalStateException("Space " + resourceId
                        + " is not mapped to an OPERA function space; run Setup from OPERA"));
    }

    /** Mapped OPERA code of a local code, or the local code itself when OPERA knows it. */
    private Optional<String> mapped(OperaHotel hotel, OperaCodeMap.Kind kind, String local) {
        Optional<String> byMap = mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(
                        hotel.getTenantId(), hotel.getId(), kind, local)
                .map(OperaCodeMap::getOperaCode)
                .filter(StringUtils::hasText);
        if (byMap.isPresent()) {
            return byMap;
        }
        return codeRepo.findByTenantIdAndOperaHotelIdAndKindAndCode(hotel.getTenantId(), hotel.getId(), kind.codeKind(), local)
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .map(OperaCode::getCode);
    }

    private String operaRoomType(OperaHotel hotel, Long resourceId) {
        return mapRepo.findByTenantIdAndOperaHotelIdAndKindAndLocalRef(
                        hotel.getTenantId(), hotel.getId(), OperaCodeMap.Kind.ROOM_TYPE, resourceId.toString())
                .map(OperaCodeMap::getOperaCode)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new IllegalStateException(
                        "Map room resource " + resourceId + " to an OPERA ROOM_TYPE before syncing the block"));
    }

    private BigDecimal rateOf(BookingRoomBlock block, LocalDate night) {
        return reservationRepo.findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(block.getPropertyTenantId(), block.getId())
                .stream()
                .filter(r -> r.getStartsAt() != null && r.getStartsAt().toLocalDate().equals(night))
                .filter(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()))
                .map(Reservation::getUnitPrice)
                .filter(p -> p != null && p.signum() > 0)
                .findFirst()
                .orElse(null);
    }

    public OperaStatusGraph graph(OperaHotel hotel) {
        OperaStatusGraph graph = new OperaStatusGraph(codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(
                hotel.getTenantId(), hotel.getId(), OperaCode.Kind.BLOCK_STATUS));
        if (graph.isEmpty()) {
            throw new IllegalStateException("Import OPERA block statuses for " + hotel.getHotelCode() + " first");
        }
        return graph;
    }

    private BookingStatus currentStatus(Booking booking) {
        if (!StringUtils.hasText(booking.getStatusCode())) {
            return null;
        }
        return statusRepo.findByTenantIdAndCode(booking.getTenantId(), booking.getStatusCode()).orElse(null);
    }

    private static String operaCode(BookingStatus status, OperaStatusGraph graph) {
        return operaCode(status, status.getPhase(), graph);
    }

    /** OPERA code of a local status: its own link, otherwise the first OPERA status of the same phase. */
    static String operaCode(BookingStatus status, Booking.Status phase, OperaStatusGraph graph) {
        if (status != null && StringUtils.hasText(status.getOperaCode()) && graph.find(status.getOperaCode()).isPresent()) {
            return graph.find(status.getOperaCode()).get().getCode();
        }
        return graph.firstOfPhase(phase).orElseThrow(() -> new IllegalStateException(
                "No OPERA block status stands for " + phase + "; link the booking status to an OPERA code"));
    }

    private List<OperaHotel> activeHotels(Long propertyTenantId) {
        return hotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(propertyTenantId).stream()
                .filter(h -> !Boolean.FALSE.equals(h.getActive()))
                .toList();
    }

    private String reservationType(OperaHotel hotel, boolean deduct) {
        String configured = deduct ? hotel.getDefaultDeductReservationType() : hotel.getDefaultNonDeductReservationType();
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        return codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(
                        hotel.getTenantId(), hotel.getId(), OperaCode.Kind.GUARANTEE).stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .filter(c -> deduct == c.getAttrs().path("reserveInventory").asBoolean(false))
                .map(OperaCode::getCode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Set default " + (deduct ? "deduct" : "non-deduct")
                                + " reservation type on the OPERA hotel (or import guarantee codes)"));
    }

    private String requireCode(String configured, OperaHotel hotel, OperaCode.Kind kind, String label) {
        if (StringUtils.hasText(configured)) {
            return configured.trim();
        }
        return codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(hotel.getTenantId(), hotel.getId(), kind).stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .map(OperaCode::getCode)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Set default " + label + " on the OPERA hotel (or import " + kind + " codes)"));
    }

    private static ObjectNode blockId(BookingOperaBlock row) {
        ObjectNode id = MAPPER.createObjectNode();
        id.put("type", "Block");
        id.put("idContext", "OPERA");
        id.put("id", row.getBlockId());
        return id;
    }

    /**
     * OPERA DH template is {@code YYMMAAAAAA}: 2-digit year, month, then 6 letters (A–Z).
     * Built from the booking start date and id so each booking gets a stable, unique code.
     */
    static String blockCode(Booking booking) {
        LocalDate from = booking.getDateFrom() != null ? booking.getDateFrom() : LocalDate.now();
        long id = booking.getId() != null ? booking.getId() : 0L;
        long n = Math.floorMod(id, 26L * 26 * 26 * 26 * 26 * 26);
        char[] seq = new char[6];
        for (int i = 5; i >= 0; i--) {
            seq[i] = (char) ('A' + (n % 26));
            n /= 26;
        }
        return String.format(Locale.ROOT, "%02d%02d%s", from.getYear() % 100, from.getMonthValue(), new String(seq));
    }

    static String extractBlockId(String location, JsonNode body) {
        String fromLocation = idAfter(location, "/blocks/");
        if (fromLocation != null) {
            return fromLocation;
        }
        if (StringUtils.hasText(location)) {
            String path = stripQuery(location);
            int last = path.lastIndexOf('/');
            if (last >= 0 && last < path.length() - 1) {
                return path.substring(last + 1);
            }
        }
        if (body != null) {
            JsonNode id = body.at("/blocks/blockInfo/0/block/blockIdList/0/id");
            if (!id.isMissingNode() && StringUtils.hasText(id.asText())) {
                return id.asText();
            }
        }
        return null;
    }

    static String extractEventId(String location, JsonNode body) {
        String fromLocation = idAfter(location, "/events/");
        if (fromLocation != null) {
            return fromLocation;
        }
        return body == null ? null : findEventId(body);
    }

    private static String findEventId(JsonNode node) {
        if (node.isObject()) {
            JsonNode eventId = node.get("eventId");
            if (eventId != null && eventId.hasNonNull("id") && !"-1".equals(eventId.get("id").asText())) {
                return eventId.get("id").asText();
            }
        }
        for (JsonNode child : node) {
            if (child.isContainerNode()) {
                String found = findEventId(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String idAfter(String location, String marker) {
        if (!StringUtils.hasText(location)) {
            return null;
        }
        String path = stripQuery(location);
        int idx = path.toLowerCase(Locale.ROOT).lastIndexOf(marker);
        if (idx < 0) {
            return null;
        }
        String id = path.substring(idx + marker.length());
        int slash = id.indexOf('/');
        id = slash >= 0 ? id.substring(0, slash) : id;
        return StringUtils.hasText(id) ? id : null;
    }

    private static String stripQuery(String location) {
        return location.contains("?") ? location.substring(0, location.indexOf('?')) : location;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
