package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Nightly room free counts from OHIP INV ({@code /inv/v1/hotels/{h}/hotelInventory}).
 * Uses inventory (not PAR rate-shopping). Tentative holds are included unless callers opt out.
 */
@Service
public class OperaRoomAvailabilityService {
    private static final Logger log = LoggerFactory.getLogger(OperaRoomAvailabilityService.class);
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

    private final OperaPostingClient client;
    private final OperaTenantConfigResolver configResolver;

    public OperaRoomAvailabilityService(OperaPostingClient client, OperaTenantConfigResolver configResolver) {
        this.client = client;
        this.configResolver = configResolver;
    }

    /**
     * Opera room-type code → free units per night. Missing keys are not available / not reported.
     * {@code arrival} inclusive, {@code departure} exclusive (last night = departure − 1).
     * Includes tentative (TEN) inventory by default.
     */
    public Map<String, Map<LocalDate, Integer>> freeUnitsByRoomTypeAndNight(OperaHotel hotel,
                                                                            LocalDate arrival,
                                                                            LocalDate departure,
                                                                            int roomsRequested) {
        return freeUnitsByRoomTypeAndNight(hotel, arrival, departure, roomsRequested, true);
    }

    /**
     * @param includeTentative {@code true} → Opera {@code includeTentativeInventory=true} (TEN counts as taken);
     *                         {@code false} → only definite inventory.
     */
    public Map<String, Map<LocalDate, Integer>> freeUnitsByRoomTypeAndNight(OperaHotel hotel,
                                                                            LocalDate arrival,
                                                                            LocalDate departure,
                                                                            int roomsRequested,
                                                                            boolean includeTentative) {
        if (hotel == null || !StringUtils.hasText(hotel.getHotelCode())) {
            throw new IllegalArgumentException("OPERA hotel is required");
        }
        if (arrival == null || departure == null || !departure.isAfter(arrival)) {
            throw new IllegalArgumentException("departure must be after arrival");
        }
        LocalDate lastNight = departure.minusDays(1);
        int qty = Math.max(roomsRequested, 1);
        OperaTenantConfigResolver.OperaResolvedConfig config = configResolver.resolve(hotel.getTenantId());
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("dateRangeStart", List.of(arrival.toString()));
        query.put("dateRangeEnd", List.of(lastNight.toString()));
        query.put("roomCountRequested", List.of(String.valueOf(qty)));
        query.put("dailyInventory", List.of("true"));
        query.put("includeTentativeInventory", List.of(includeTentative ? "true" : "false"));
        JsonNode response = client.get(config, hotel.getChainCode(), hotel.getHotelCode(),
                "/inv/v1/hotels/" + hotel.getHotelCode() + "/hotelInventory", query);
        Map<String, Map<LocalDate, Integer>> free = new HashMap<>();
        parse(response, free);
        log.debug("OPERA inventory hotel={} {}→{} rooms={} includeTentative={}: {} types",
                hotel.getHotelCode(), arrival, departure, qty, includeTentative, free.size());
        return free;
    }

    /** Opera code on a synced room pool: {@code operaRoomId}, else resource {@code code}. */
    public static String operaCodeOf(Resource roomType) {
        if (roomType == null) {
            return null;
        }
        if (StringUtils.hasText(roomType.getOperaRoomId())) {
            return roomType.getOperaRoomId().trim().toUpperCase(Locale.ROOT);
        }
        return roomType.getCode() != null ? roomType.getCode().trim().toUpperCase(Locale.ROOT) : null;
    }

    /** Lowest free over the reported nights; null when the type has no counts. */
    public static Integer minFree(Map<LocalDate, Integer> nights) {
        if (nights == null || nights.isEmpty()) {
            return null;
        }
        int lowest = Integer.MAX_VALUE;
        for (Integer free : nights.values()) {
            if (free == null) {
                continue;
            }
            lowest = Math.min(lowest, free);
        }
        return lowest == Integer.MAX_VALUE ? null : lowest;
    }

    static void parse(JsonNode response, Map<String, Map<LocalDate, Integer>> freeByType) {
        if (response == null || response.isNull()) {
            return;
        }
        JsonNode hotels = response.path("hotelInventories");
        if (!hotels.isArray()) {
            return;
        }
        for (JsonNode hotel : hotels) {
            JsonNode roomTypes = hotel.path("roomTypeInventories");
            if (!roomTypes.isArray()) {
                continue;
            }
            for (JsonNode roomType : roomTypes) {
                String code = text(roomType, "code");
                if (code == null) {
                    continue;
                }
                String key = code.toUpperCase(Locale.ROOT);
                Map<LocalDate, Integer> nights = freeByType.computeIfAbsent(key, ignored -> new TreeMap<>());
                JsonNode counts = roomType.path("inventoryCounts");
                if (!counts.isArray()) {
                    continue;
                }
                for (JsonNode count : counts) {
                    Integer available = intOrNull(count.get("availableCount"));
                    if (available == null) {
                        continue;
                    }
                    LocalDate start = parseDate(text(count, "startDate"));
                    LocalDate end = parseDate(text(count, "endDate"));
                    if (start == null) {
                        continue;
                    }
                    LocalDate last = end != null && !end.isBefore(start) ? end : start;
                    for (LocalDate night = start; !night.isAfter(last); night = night.plusDays(1)) {
                        nights.merge(night, available, Math::min);
                    }
                }
            }
        }
    }

    private static LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.length() == 8 && value.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(value, COMPACT);
            }
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
