package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.dto.OperaReservationSearchResultDto;
import com.stackwizard.booking_api.model.OperaHotel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OperaReservationSearchService {
    private final OperaPostingClient operaPostingClient;
    private final OperaTenantConfigResolver tenantConfigResolver;
    private final OperaPostingConfigurationService configurationService;

    public OperaReservationSearchService(OperaPostingClient operaPostingClient,
                                         OperaTenantConfigResolver tenantConfigResolver,
                                         OperaPostingConfigurationService configurationService) {
        this.operaPostingClient = operaPostingClient;
        this.tenantConfigResolver = tenantConfigResolver;
        this.configurationService = configurationService;
    }

    public List<OperaReservationSearchResultDto> searchInHouseReservations(Long tenantId,
                                                                           String hotelCode,
                                                                           LocalDate arrivalDate,
                                                                           String roomId,
                                                                           String givenName,
                                                                           String surname,
                                                                           String customerQuery) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String resolvedHotelCode = normalizeHotelCode(hotelCode);
        if (!StringUtils.hasText(resolvedHotelCode)) {
            throw new IllegalArgumentException("hotelCode is required");
        }
        LocalDate resolvedArrivalDate = arrivalDate != null ? arrivalDate : LocalDate.now();

        OperaHotel hotel = configurationService.requireActiveHotel(tenantId, resolvedHotelCode);
        OperaTenantConfigResolver.OperaResolvedConfig config = tenantConfigResolver.resolve(tenantId);
        JsonNode response = operaPostingClient.getReservations(
                config,
                normalizeNullable(hotel.getChainCode()),
                hotel.getHotelCode(),
                buildQueryParams(
                        resolvedArrivalDate,
                        normalizeNullable(roomId),
                        normalizeNullable(surname)
                )
        );

        return mapResponse(
                response,
                hotel,
                normalizeNullable(roomId),
                normalizeNullable(givenName),
                normalizeNullable(surname),
                normalizeNullable(customerQuery)
        );
    }

    private Map<String, List<String>> buildQueryParams(LocalDate arrivalDate,
                                                       String roomId,
                                                       String surname) {
        String day = arrivalDate.toString();
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("unlinkedOnly", List.of("false"));
        params.put("actualDepartures", List.of("false"));
        params.put("complimentaryReservations", List.of("false"));
        params.put("arrivalStartDate", List.of(encode(day)));
        params.put("arrivalEndDate", List.of(encode(day)));
        params.put("allowedReservationActions", List.of(
                encode("FacilitySchedule"),
                encode("PreCharge"),
                encode("PostCharge"),
                encode("PostToNoShowCancel"),
                encode("HouseKeeping")
        ));
        params.put("hasOpenFolio", List.of("false"));
        params.put("expectedArrivals", List.of("false"));
        params.put("reservationStatuses", List.of(encode("InHouse")));
        params.put("expectedDepartures", List.of("false"));
        params.put("excludeBlockReservations", List.of("false"));
        params.put("limit", List.of("100"));
        params.put("dayOfArrivalCancels", List.of("false"));
        params.put("earlyDepartures", List.of("false"));
        params.put("offset", List.of("1"));
        params.put("excludeNoPost", List.of("false"));
        params.put("discountApplied", List.of("false"));
        params.put("roomAssignedOnly", List.of("true"));
        params.put("linkedOnly", List.of("false"));
        params.put("excludePMRooms", List.of("false"));
        params.put("actualArrivals", List.of("false"));
        params.put("roomUnassignedOnly", List.of("false"));
        params.put("stayovers", List.of("false"));
        params.put("dayUse", List.of("false"));
        params.put("recentlyAccessed", List.of("false"));
        params.put("houseUseReservations", List.of("false"));
        if (StringUtils.hasText(roomId)) {
            params.put("roomId", List.of(encode(roomId)));
        }
        if (StringUtils.hasText(surname)) {
            params.put("surname", List.of(encode(surname)));
        }
        return params;
    }

    private List<OperaReservationSearchResultDto> mapResponse(JsonNode response,
                                                              OperaHotel hotel,
                                                              String roomId,
                                                              String givenName,
                                                              String surname,
                                                              String customerQuery) {
        JsonNode reservationInfo = response.path("reservations").path("reservationInfo");
        if (!reservationInfo.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(reservationInfo.spliterator(), false)
                .map(node -> toResult(node, hotel))
                .filter(result -> result.getReservationId() != null)
                .filter(result -> matchesFilters(result, roomId, givenName, surname, customerQuery))
                .toList();
    }

    private OperaReservationSearchResultDto toResult(JsonNode node, OperaHotel hotel) {
        return OperaReservationSearchResultDto.builder()
                .reservationId(parseReservationId(node.path("reservationIdList")))
                .hotelCode(hotel.getHotelCode())
                .hotelName(firstNonBlank(text(node.path("hotelName")), hotel.getName()))
                .confirmationNumber(parseConfirmation(node.path("reservationIdList")))
                .roomId(text(node.path("roomStay").path("roomId")))
                .arrivalDate(text(node.path("roomStay").path("arrivalDate")))
                .departureDate(text(node.path("roomStay").path("departureDate")))
                .customerName(buildCustomerName(node.path("reservationGuest")))
                .customerEmail(text(node.path("reservationGuest").path("email")))
                .customerPhone(text(node.path("reservationGuest").path("phoneNumber")))
                .operaProfileId(text(node.path("reservationGuest").path("id")))
                .build();
    }

    private boolean matchesFilters(OperaReservationSearchResultDto result,
                                   String roomId,
                                   String givenName,
                                   String surname,
                                   String customerQuery) {
        if (StringUtils.hasText(roomId) && !containsIgnoreCase(result.getRoomId(), roomId)) {
            return false;
        }
        if (StringUtils.hasText(givenName) && !containsIgnoreCase(result.getCustomerName(), givenName)) {
            return false;
        }
        if (StringUtils.hasText(surname) && !containsIgnoreCase(result.getCustomerName(), surname)) {
            return false;
        }
        if (!StringUtils.hasText(customerQuery)) {
            return true;
        }
        String normalizedCustomerQuery = customerQuery.toLowerCase();
        String haystack = String.join(" ",
                safe(result.getCustomerName()),
                safe(result.getCustomerEmail()),
                safe(result.getCustomerPhone()),
                safe(result.getConfirmationNumber()),
                safe(result.getRoomId())).toLowerCase();
        return haystack.contains(normalizedCustomerQuery);
    }

    private boolean containsIgnoreCase(String value, String search) {
        return safe(value).toLowerCase().contains(safe(search).toLowerCase());
    }

    private Long parseReservationId(JsonNode reservationIdList) {
        if (reservationIdList == null || !reservationIdList.isArray()) {
            return null;
        }
        for (JsonNode idNode : reservationIdList) {
            if ("Reservation".equalsIgnoreCase(text(idNode.path("type")))) {
                try {
                    return Long.valueOf(idNode.path("id").asText());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String parseConfirmation(JsonNode reservationIdList) {
        if (reservationIdList == null || !reservationIdList.isArray()) {
            return null;
        }
        for (JsonNode idNode : reservationIdList) {
            if ("Confirmation".equalsIgnoreCase(text(idNode.path("type")))) {
                return normalizeNullable(idNode.path("id").asText(null));
            }
        }
        return null;
    }

    private String buildCustomerName(JsonNode reservationGuest) {
        String given = text(reservationGuest.path("givenName"));
        String surname = text(reservationGuest.path("surname"));
        return firstNonBlank(
                normalizeNullable((safe(given) + " " + safe(surname)).trim()),
                surname
        );
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return normalizeNullable(node.asText(null));
    }

    private String normalizeHotelCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
