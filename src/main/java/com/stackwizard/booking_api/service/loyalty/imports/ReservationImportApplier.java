package com.stackwizard.booking_api.service.loyalty.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.model.LoyaltyReservation;
import com.stackwizard.booking_api.repository.LoyaltyReservationRepository;
import com.stackwizard.booking_api.service.loyalty.LoyaltyNormalizer;
import com.stackwizard.booking_api.service.loyalty.LoyaltyReservationService;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PMS reservations from an OPERA export (one hotel per file). Upsert by confirmation number; any change that can
 * affect qualification (status, dates, holder, card number, channel codes, exclusivity) queues a re-evaluation.
 * A reservation missing from a later export is left alone: exports are often partial by date.
 */
@Component
public class ReservationImportApplier implements ImportApplier, RequiredFields {
    /** OPERA reservation statuses (and common spellings) → loyalty status. Overridable per batch with options.statusMap. */
    static final Map<String, LoyaltyReservation.Status> DEFAULT_STATUS = Map.ofEntries(
            Map.entry("RESERVED", LoyaltyReservation.Status.RESERVED),
            Map.entry("NEW", LoyaltyReservation.Status.RESERVED),
            Map.entry("DUEIN", LoyaltyReservation.Status.RESERVED),
            Map.entry("RES", LoyaltyReservation.Status.RESERVED),
            Map.entry("CONFIRMED", LoyaltyReservation.Status.RESERVED),
            Map.entry("WAITLIST", LoyaltyReservation.Status.RESERVED),
            Map.entry("PROSPECT", LoyaltyReservation.Status.RESERVED),
            Map.entry("INHOUSE", LoyaltyReservation.Status.IN_HOUSE),
            Map.entry("CHECKEDIN", LoyaltyReservation.Status.IN_HOUSE),
            Map.entry("DUEOUT", LoyaltyReservation.Status.IN_HOUSE),
            Map.entry("CHECKEDOUT", LoyaltyReservation.Status.CHECKED_OUT),
            Map.entry("CHECKOUT", LoyaltyReservation.Status.CHECKED_OUT),
            Map.entry("DEPARTED", LoyaltyReservation.Status.CHECKED_OUT),
            Map.entry("CANCELLED", LoyaltyReservation.Status.CANCELLED),
            Map.entry("CANCELED", LoyaltyReservation.Status.CANCELLED),
            Map.entry("CANCEL", LoyaltyReservation.Status.CANCELLED),
            Map.entry("CXL", LoyaltyReservation.Status.CANCELLED),
            Map.entry("NOSHOW", LoyaltyReservation.Status.NO_SHOW));

    private final LoyaltyReservationRepository reservationRepo;
    private final LoyaltyReservationService reservationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReservationImportApplier(LoyaltyReservationRepository reservationRepo, LoyaltyReservationService reservationService) {
        this.reservationRepo = reservationRepo;
        this.reservationService = reservationService;
    }

    @Override
    public LoyaltyImportBatch.ImportType type() {
        return LoyaltyImportBatch.ImportType.RESERVATIONS;
    }

    @Override
    public List<String> requiredFields() {
        return List.of("confirmation_no", "status", "arrival", "departure");
    }

    @Override
    public Map<String, List<String>> fields() {
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("confirmation_no", List.of("confirmation_number", "conf_no", "confirmation", "resv_no", "reservation_number", "reservation_id"));
        f.put("sub_id", List.of("resv_name_id", "name_id", "sharer_id", "room_no_seq"));
        f.put("status", List.of("resv_status", "reservation_status", "res_status"));
        f.put("arrival", List.of("arrival_date", "check_in", "checkin", "begin_date"));
        f.put("departure", List.of("departure_date", "check_out", "checkout", "end_date"));
        f.put("nights", List.of("no_of_nights", "room_nights", "los"));
        f.put("units", List.of("no_of_rooms", "rooms", "number_of_rooms"));
        f.put("room_type", List.of("room_category", "roomtype"));
        f.put("source_code", List.of("source", "source_of_business"));
        f.put("market_code", List.of("market", "market_segment", "segment"));
        f.put("channel_code", List.of("channel", "origin", "origin_code", "booking_channel"));
        f.put("rate_code", List.of("rate", "rate_plan"));
        f.put("holder_first_name", List.of("first_name", "guest_first_name", "firstname"));
        f.put("holder_last_name", List.of("last_name", "guest_last_name", "surname", "guest_name", "name"));
        f.put("holder_email", List.of("email", "guest_email", "e_mail"));
        f.put("holder_phone", List.of("phone", "guest_phone", "mobile"));
        f.put("membership_no", List.of("membership_number", "member_number", "card_number", "loyalty_number", "arena_rewards"));
        f.put("exclusivity_flag", List.of("radisson", "radisson_rewards", "other_programme", "exclusive_flag"));
        f.put("cancelled_at", List.of("cancellation_date", "cancel_date"));
        f.put("amount", List.of("revenue", "total_revenue", "room_revenue"));
        f.put("currency", List.of("currency_code"));
        return f;
    }

    private record State(Set<String> keys, Map<String, LoyaltyReservation.Status> statusMap) {
    }

    @Override
    public Object newValidationState(LoyaltyImportBatch batch) {
        return new State(new HashSet<>(), statusMap(batch.getOptions()));
    }

    @Override
    public String validate(LoyaltyImportBatch batch, ImportRow row, Object st) {
        State state = (State) st;
        String conf = row.required("confirmation_no");
        String rawStatus = row.required("status");
        var arrival = row.date("arrival");
        var departure = row.date("departure");
        if (arrival == null && row.text("arrival") == null) {
            row.error("arrival is required");
        }
        if (departure == null && row.text("departure") == null) {
            row.error("departure is required");
        }
        if (arrival != null && departure != null && departure.isBefore(arrival)) {
            row.error("departure is before arrival");
        }
        if (rawStatus != null && mapStatus(rawStatus, state.statusMap()) == null) {
            row.error("unknown status " + rawStatus + " (map it with options.statusMap)");
        }
        Integer units = row.integer("units");
        if (units != null && units <= 0) {
            row.error("units must be positive");
        }
        row.integer("nights");
        row.bool("exclusivity_flag");
        row.date("cancelled_at");
        row.decimal("amount");
        String email = row.text("holder_email");
        if (email != null && LoyaltyNormalizer.email(email) == null) {
            row.error("holder_email is not valid: " + email);
        }
        if (!row.errors().isEmpty()) {
            return null;
        }
        String key = conf + "|" + Objects.toString(row.text("sub_id"), "");
        if (!state.keys().add(key)) {
            return "reservation " + key + " appears more than once in the file (add a sub_id column for sharers)";
        }
        return null;
    }

    @Override
    public Long apply(LoyaltyImportBatch batch, ImportRow row) {
        LoyaltyReservation.ExternalSystem system = externalSystem(batch.getOptions());
        String conf = row.text("confirmation_no");
        String subId = Objects.toString(row.text("sub_id"), "");
        LoyaltyReservation existing = reservationRepo
                .findByPropertyTenantIdAndExternalSystemAndConfirmationNoAndExternalSubId(batch.getPropertyTenantId(), system, conf, subId)
                .orElse(null);
        LoyaltyReservation r = existing != null ? existing : LoyaltyReservation.builder()
                .tenantId(batch.getTenantId())
                .programId(batch.getProgramId())
                .propertyTenantId(batch.getPropertyTenantId())
                .externalSystem(system)
                .confirmationNo(conf)
                .externalSubId(subId)
                .firstSeenBatchId(batch.getId())
                .exclusivityFlag(false)
                .build();
        String before = existing == null ? null : fingerprint(existing);

        var arrival = row.date("arrival");
        var departure = row.date("departure");
        r.setStatus(mapStatus(row.text("status"), statusMap(batch.getOptions())));
        r.setArrival(arrival);
        r.setDeparture(departure);
        Integer nights = row.integer("nights");
        r.setNights(nights != null ? nights : (int) ChronoUnit.DAYS.between(arrival, departure));
        Integer units = row.integer("units");
        r.setUnits(units != null ? units : 1);
        r.setRoomType(LoyaltyNormalizer.text(row.text("room_type")));
        r.setSourceCode(LoyaltyNormalizer.code(row.text("source_code")));
        r.setMarketCode(LoyaltyNormalizer.code(row.text("market_code")));
        r.setChannelCode(LoyaltyNormalizer.code(row.text("channel_code")));
        r.setRateCode(LoyaltyNormalizer.code(row.text("rate_code")));
        r.setHolderFirstName(LoyaltyNormalizer.text(row.text("holder_first_name")));
        r.setHolderLastName(LoyaltyNormalizer.text(row.text("holder_last_name")));
        r.setHolderEmailNorm(LoyaltyNormalizer.email(row.text("holder_email")));
        r.setHolderPhoneNorm(LoyaltyNormalizer.phone(row.text("holder_phone")));
        r.setMembershipNo(LoyaltyNormalizer.text(row.text("membership_no")));
        r.setExclusivityFlag(Boolean.TRUE.equals(row.bool("exclusivity_flag")));
        r.setCancelledAt(row.date("cancelled_at"));
        r.setAmount(row.decimal("amount"));
        r.setCurrency(LoyaltyNormalizer.code(row.text("currency")));
        r.setRaw(rawCopy(row));
        r.setLastSeenBatchId(batch.getId());
        r.setExportAsOf(OffsetDateTime.now());
        r.setUpdatedAt(OffsetDateTime.now());
        if (!row.errors().isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", row.errors()));
        }
        r = reservationRepo.save(r);
        if (before == null || !before.equals(fingerprint(r))) {
            reservationService.enqueue(r);
        }
        return r.getId();
    }

    /** Everything qualification depends on; equal fingerprints mean a re-import needs no re-evaluation. */
    static String fingerprint(LoyaltyReservation r) {
        return String.join("|", String.valueOf(r.getStatus()), String.valueOf(r.getArrival()), String.valueOf(r.getDeparture()),
                Objects.toString(r.getMembershipNo(), ""), Objects.toString(r.getHolderEmailNorm(), ""),
                Objects.toString(r.getHolderLastName(), ""), Objects.toString(r.getSourceCode(), ""),
                Objects.toString(r.getMarketCode(), ""), Objects.toString(r.getChannelCode(), ""),
                String.valueOf(r.getExclusivityFlag()));
    }

    static LoyaltyReservation.Status mapStatus(String raw, Map<String, LoyaltyReservation.Status> map) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return map.get(key);
    }

    private static Map<String, LoyaltyReservation.Status> statusMap(JsonNode options) {
        Map<String, LoyaltyReservation.Status> map = new java.util.HashMap<>(DEFAULT_STATUS);
        JsonNode custom = options == null ? null : options.get("statusMap");
        if (custom != null && custom.isObject()) {
            custom.fields().forEachRemaining(e -> {
                try {
                    map.put(e.getKey().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", ""),
                            LoyaltyReservation.Status.valueOf(e.getValue().asText().trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("options.statusMap: unknown target status " + e.getValue().asText());
                }
            });
        }
        return map;
    }

    private static LoyaltyReservation.ExternalSystem externalSystem(JsonNode options) {
        String s = options == null ? null : options.path("externalSystem").asText(null);
        return s == null || s.isBlank() ? LoyaltyReservation.ExternalSystem.OPERA_V5 : LoyaltyReservation.ExternalSystem.valueOf(s.trim().toUpperCase(Locale.ROOT));
    }

    private ObjectNode rawCopy(ImportRow row) {
        ObjectNode raw = objectMapper.createObjectNode();
        for (String field : fields().keySet()) {
            String v = row.text(field);
            if (v != null) {
                raw.put(field, v);
            }
        }
        return raw;
    }
}
