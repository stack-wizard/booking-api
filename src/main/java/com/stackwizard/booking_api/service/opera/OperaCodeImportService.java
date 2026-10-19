package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.OperaDtos;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.repository.OperaCodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the code lists sales needs from a hotel's OPERA configuration into {@code opera_code}. Each kind is fetched
 * and stored on its own, so a module the property does not license fails alone.
 */
@Service
public class OperaCodeImportService {
    private static final Logger log = LoggerFactory.getLogger(OperaCodeImportService.class);

    /** How a list endpoint pages: not at all, by {@code pageNumber} (from 1) or by record {@code offset} (from 0). */
    enum Paging { NONE, PAGE_NUMBER, OFFSET }

    /** OHIP list endpoints often cap at 100 (e.g. functionSpaces); larger values return 400. */
    static final int PAGE_SIZE = 100;
    /** A kind with more pages than this is refused rather than stored partially (that would deactivate codes). */
    static final int MAX_PAGES = 50;

    /**
     * Where a kind is read from. {@code items} is a dotted path to the list (arrays on the way are flattened);
     * {@code attrs} maps stored attribute names to dotted paths inside an item.
     */
    record Source(OperaCode.Kind kind, String path, Map<String, String> query, Paging paging, String items,
                  String code, String description, Map<String, String> attrs) {
    }

    static final List<Source> SOURCES = List.of(
            new Source(OperaCode.Kind.BLOCK_STATUS, "/blk/config/v1/blockStatusCodes/nextStatusCodes",
                    Map.of("configuredOnly", "true"), Paging.NONE, "blockStatusCodeMasterList", "status.code", "status.description",
                    attrs("roomStatusType", "roomStatusType", "cateringStatusType", "cateringStatusType",
                            "color", "color", "starting", "starting", "orderBy", "orderBy",
                            "allowPickup", "allowPickup", "returnToInventory", "returnToInventory",
                            "defaultReservationType", "defaultReservationType.code")),
            new Source(OperaCode.Kind.EVENT_TYPE, "/evm/config/v1/cateringEventTypes",
                    Map.of(), Paging.NONE, "eventTypes", "eventTypeCode", "description",
                    attrs("mealType", "mealType", "inactive", "inactive")),
            new Source(OperaCode.Kind.SETUP_STYLE, "/evm/config/v1/functionspaceSetupStyles",
                    Map.of("fetchInactive", "false"), Paging.NONE, "setupStyles", "code", "translatableDescription",
                    attrs("inactive", "inactive")),
            new Source(OperaCode.Kind.FUNCTION_SPACE, "/evm/config/v1/hotels/{hotelId}/functionSpaces",
                    Map.of("includeInactive", "false"), Paging.PAGE_NUMBER, "functionSpaces", "roomCode", "spaceName",
                    attrs("spaceType", "spaceType", "shortName", "shortName", "roomType", "roomType",
                            "combo", "combo", "shareable", "shareable", "inactive", "inactive")),
            new Source(OperaCode.Kind.MARKET, "/rsv/config/v1/marketCodes",
                    Map.of("hotelIds", "{hotelId}", "includeInactive", "false"), Paging.PAGE_NUMBER, "marketCodes", "code", "description",
                    attrs("groupCode", "groupCode", "inactive", "inactive")),
            new Source(OperaCode.Kind.SOURCE, "/rsv/config/v1/hotels/{hotelId}/sourceCodes",
                    Map.of("hotelIds", "{hotelId}", "includeInactive", "false"), Paging.PAGE_NUMBER, "sourceCodes", "code", "description",
                    attrs("groupCode", "groupCode", "inactive", "inactive")),
            new Source(OperaCode.Kind.GUARANTEE, "/rsv/config/v1/guaranteeCodes",
                    Map.of("hotelIds", "{hotelId}"), Paging.PAGE_NUMBER, "guaranteeCodes.guaranteeCode", "guaranteeCode", "description",
                    attrs("reserveInventory", "reserveInventory", "onHold", "onHold", "inactive", "inactive")),
            new Source(OperaCode.Kind.ROOM_TYPE, "/rm/config/v1/hotels/{hotelId}/roomTypes",
                    Map.of("hotelIds", "{hotelId}", "summaryInfo", "true", "includeInactive", "false"), Paging.OFFSET,
                    "roomTypesSummary.roomTypeSummary", "roomType", "shortDescription",
                    attrs("roomClass", "roomClass", "numberOfRooms", "numberOfRooms", "pseudo", "pseudo",
                            "meetingRoom", "meetingRoom", "inactive", "inactive")),
            new Source(OperaCode.Kind.RATE_CODE, "/rtp/v1/ratePlans",
                    Map.of("hotelId", "{hotelId}", "includeInactive", "false"), Paging.OFFSET,
                    "ratePlanShortInfoList.ratePlanShortInfo", "ratePlanCode", "primaryDetails.description",
                    attrs("rateCategory", "classifications.rateCategory", "marketCode", "classifications.marketCode",
                            "sourceCode", "classifications.sourceCode", "discountAllowed", "discountAllowed")));

    /** All block statuses, for environments whose flow call returns no master list. */
    private static final Source ALL_BLOCK_STATUSES = new Source(OperaCode.Kind.BLOCK_STATUS,
            "/blk/config/v1/blockStatusCodes", Map.of(), Paging.NONE, "blockStatusCodes",
            SOURCES.get(0).code(), SOURCES.get(0).description(), SOURCES.get(0).attrs());

    record Row(String code, String description, ObjectNode attrs, boolean active) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OperaPostingClient client;
    private final OperaTenantConfigResolver configResolver;
    private final OperaCodeRepository codeRepo;
    private final TransactionTemplate transactionTemplate;

    public OperaCodeImportService(OperaPostingClient client,
                                  OperaTenantConfigResolver configResolver,
                                  OperaCodeRepository codeRepo,
                                  PlatformTransactionManager transactionManager) {
        this.client = client;
        this.configResolver = configResolver;
        this.codeRepo = codeRepo;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OperaDtos.ImportKindResult> importCodes(OperaHotel hotel) {
        OperaTenantConfigResolver.OperaResolvedConfig config = configResolver.resolve(hotel.getTenantId());
        List<OperaDtos.ImportKindResult> results = new ArrayList<>();
        for (Source source : SOURCES) {
            try {
                List<Row> rows;
                if (source.kind() == OperaCode.Kind.BLOCK_STATUS) {
                    JsonNode response = fetch(config, hotel, source, Map.of());
                    rows = parse(source, response);
                    if (rows.isEmpty()) {
                        rows = parse(ALL_BLOCK_STATUSES, fetch(config, hotel, ALL_BLOCK_STATUSES, Map.of()), response);
                    }
                } else {
                    rows = fetchAll(config, hotel, source);
                }
                List<Row> stored = rows;
                OperaDtos.ImportKindResult result = transactionTemplate.execute(tx -> store(hotel, source.kind(), stored));
                results.add(result);
            } catch (RuntimeException ex) {
                log.warn("OPERA {} import failed for hotel {}: {}", source.kind(), hotel.getHotelCode(), ex.getMessage());
                results.add(new OperaDtos.ImportKindResult(source.kind(), 0, 0, ex.getMessage()));
            }
        }
        return results;
    }

    /** Reads every page of a kind; codes repeated across pages are kept once. */
    private List<Row> fetchAll(OperaTenantConfigResolver.OperaResolvedConfig config, OperaHotel hotel, Source source) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> paging = switch (source.paging()) {
                case NONE -> Map.of();
                case PAGE_NUMBER -> Map.of("limit", String.valueOf(PAGE_SIZE), "pageNumber", String.valueOf(page + 1));
                case OFFSET -> Map.of("limit", String.valueOf(PAGE_SIZE), "offset", String.valueOf(page * PAGE_SIZE));
            };
            JsonNode response = fetch(config, hotel, source, paging);
            int before = rows.size();
            for (Row row : parse(source, response)) {
                rows.putIfAbsent(row.code(), row);
            }
            if (source.paging() == Paging.NONE || !hasMore(response) || rows.size() == before) {
                return new ArrayList<>(rows.values());
            }
        }
        throw new IllegalStateException("More than " + MAX_PAGES * PAGE_SIZE + " " + source.kind() + " codes; not imported");
    }

    private JsonNode fetch(OperaTenantConfigResolver.OperaResolvedConfig config, OperaHotel hotel, Source source,
                           Map<String, String> paging) {
        Map<String, List<String>> query = query(source, hotel.getHotelCode());
        paging.forEach((key, value) -> query.put(key, List.of(value)));
        return client.get(config, hotel.getChainCode(), hotel.getHotelCode(),
                source.path().replace("{hotelId}", hotel.getHotelCode()), query);
    }

    /** OHIP puts {@code hasMore} on the response or on the list wrapper (e.g. {@code ratePlanShortInfoList}). */
    static boolean hasMore(JsonNode response) {
        if (response.path("hasMore").asBoolean(false)) {
            return true;
        }
        for (JsonNode child : response) {
            if (child.isObject() && child.path("hasMore").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private OperaDtos.ImportKindResult store(OperaHotel hotel, OperaCode.Kind kind, List<Row> rows) {
        Map<String, OperaCode> existing = new HashMap<>();
        for (OperaCode code : codeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(hotel.getTenantId(), hotel.getId(), kind)) {
            existing.put(code.getCode(), code);
        }
        OffsetDateTime now = OffsetDateTime.now();
        int imported = 0;
        for (Row row : rows) {
            OperaCode code = existing.remove(row.code());
            if (code == null) {
                code = OperaCode.builder().tenantId(hotel.getTenantId()).operaHotelId(hotel.getId()).kind(kind).code(row.code()).build();
            }
            code.setDescription(row.description());
            code.setAttrs(row.attrs());
            code.setActive(row.active());
            code.setImportedAt(now);
            codeRepo.save(code);
            imported++;
        }
        int deactivated = 0;
        for (OperaCode gone : existing.values()) {
            if (Boolean.TRUE.equals(gone.getActive())) {
                gone.setActive(false);
                codeRepo.save(gone);
                deactivated++;
            }
        }
        return new OperaDtos.ImportKindResult(kind, imported, deactivated, null);
    }

    static List<Row> parse(Source source, JsonNode response) {
        return parse(source, response, response);
    }

    /** {@code flow} carries {@code nextBlockStatusCodeList} for block statuses. */
    static List<Row> parse(Source source, JsonNode response, JsonNode flow) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (JsonNode item : items(response, source.items())) {
            String code = text(at(item, source.code()));
            if (code == null) {
                continue;
            }
            ObjectNode attrs = MAPPER.createObjectNode();
            source.attrs().forEach((name, path) -> {
                JsonNode value = at(item, path);
                if (value != null && !value.isMissingNode() && !value.isNull()) {
                    attrs.set(name, value.isValueNode() ? value : MAPPER.getNodeFactory().textNode(text(value)));
                }
            });
            boolean active = !attrs.path("inactive").asBoolean(false);
            rows.putIfAbsent(code, new Row(code, text(at(item, source.description())), attrs, active));
        }
        if (source.kind() == OperaCode.Kind.BLOCK_STATUS) {
            addNextStatuses(flow, rows);
        }
        return new ArrayList<>(rows.values());
    }

    /** Stores the configured OPERA status flow as {@code attrs.next} (codes in OPERA order). */
    private static void addNextStatuses(JsonNode response, Map<String, Row> rows) {
        for (JsonNode entry : items(response, "nextBlockStatusCodeList")) {
            Row row = rows.get(text(at(entry, "status")));
            if (row == null) {
                continue;
            }
            List<JsonNode> next = new ArrayList<>(items(entry, "nextConfiguredStatusList"));
            next.sort(Comparator.comparingInt(n -> n.path("order").asInt(Integer.MAX_VALUE)));
            ArrayNode codes = row.attrs().putArray("next");
            for (JsonNode n : next) {
                String code = text(at(n, "status"));
                if (code != null) {
                    codes.add(code);
                }
            }
        }
    }

    private static Map<String, List<String>> query(Source source, String hotelCode) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        source.query().forEach((key, value) -> query.put(key, List.of(value.replace("{hotelId}", hotelCode))));
        return query;
    }

    static List<JsonNode> items(JsonNode root, String path) {
        List<JsonNode> current = List.of(root);
        for (String segment : path.split("\\.")) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                JsonNode child = node.path(segment);
                if (child.isArray()) {
                    child.forEach(next::add);
                } else if (!child.isMissingNode() && !child.isNull()) {
                    next.add(child);
                }
            }
            current = next;
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode node : current) {
            if (node.isArray()) {
                node.forEach(out::add);
            } else {
                out.add(node);
            }
        }
        return out;
    }

    private static JsonNode at(JsonNode item, String path) {
        JsonNode node = item;
        for (String segment : path.split("\\.")) {
            node = node.path(segment);
        }
        return node;
    }

    /** Plain text of a value, a translatable text ({@code defaultText} / {@code translatedTexts}) or a code object. */
    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            String value = node.asText();
            return StringUtils.hasText(value) ? value.trim() : null;
        }
        for (String key : List.of("defaultText", "value", "description", "code")) {
            String value = text(node.get(key));
            if (value != null) {
                return value;
            }
        }
        JsonNode translated = node.path("translatedTexts");
        if (translated.isArray() && !translated.isEmpty()) {
            return text(translated.get(0).get("value"));
        }
        return null;
    }

    private static Map<String, String> attrs(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }
}
