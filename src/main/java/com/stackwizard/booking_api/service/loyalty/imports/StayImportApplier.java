package com.stackwizard.booking_api.service.loyalty.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.model.PmsReservation;
import com.stackwizard.booking_api.service.loyalty.LoyaltyNormalizer;
import com.stackwizard.booking_api.service.pms.PmsReservationService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PMS reservations / stays from an OPERA export (one hotel per file; column names follow EXP_GENERAL_RESV_VIEW and
 * the common report headers). Each row goes through {@link PmsReservationService#upsert}, which does the matching,
 * events, loyalty queue and CRM statistics. A reservation missing from a later export is left alone.
 */
@Component
public class StayImportApplier implements ImportApplier, RequiredFields {
    /** OPERA reservation statuses (and common spellings) → normalised status. Overridable per batch with options.statusMap. */
    static final Map<String, PmsReservation.Status> DEFAULT_STATUS = Map.ofEntries(
            Map.entry("PROSPECT", PmsReservation.Status.PROSPECT),
            Map.entry("REQUESTED", PmsReservation.Status.PROSPECT),
            Map.entry("WAITLIST", PmsReservation.Status.WAITLIST),
            Map.entry("WAITLISTED", PmsReservation.Status.WAITLIST),
            Map.entry("RESERVED", PmsReservation.Status.RESERVED),
            Map.entry("NEW", PmsReservation.Status.RESERVED),
            Map.entry("DUEIN", PmsReservation.Status.RESERVED),
            Map.entry("RES", PmsReservation.Status.RESERVED),
            Map.entry("CONFIRMED", PmsReservation.Status.RESERVED),
            Map.entry("INHOUSE", PmsReservation.Status.IN_HOUSE),
            Map.entry("CHECKEDIN", PmsReservation.Status.IN_HOUSE),
            Map.entry("DUEOUT", PmsReservation.Status.IN_HOUSE),
            Map.entry("PENDINGCHECKOUT", PmsReservation.Status.IN_HOUSE),
            Map.entry("CHECKEDOUT", PmsReservation.Status.CHECKED_OUT),
            Map.entry("CHECKOUT", PmsReservation.Status.CHECKED_OUT),
            Map.entry("DEPARTED", PmsReservation.Status.CHECKED_OUT),
            Map.entry("CANCELLED", PmsReservation.Status.CANCELLED),
            Map.entry("CANCELED", PmsReservation.Status.CANCELLED),
            Map.entry("CANCEL", PmsReservation.Status.CANCELLED),
            Map.entry("CXL", PmsReservation.Status.CANCELLED),
            Map.entry("NOSHOW", PmsReservation.Status.NO_SHOW));

    private final PmsReservationService reservationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StayImportApplier(PmsReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @Override
    public LoyaltyImportBatch.ImportType type() {
        return LoyaltyImportBatch.ImportType.STAYS;
    }

    @Override
    public List<String> requiredFields() {
        return List.of("status", "arrival", "departure");
    }

    @Override
    public Map<String, List<String>> fields() {
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("pms_reservation_id", List.of("resv_name_id", "reservation_id", "resv_id", "name_id_resv"));
        f.put("confirmation_no", List.of("confirmation_number", "conf_no", "confirmation", "resv_no", "reservation_number"));
        f.put("confirmation_leg_no", List.of("leg_no", "leg", "confirmation_leg"));
        f.put("parent_pms_reservation_id", List.of("parent_resv_name_id", "parent_reservation_id"));
        f.put("external_reference", List.of("crs_confirmation_no", "crs_number", "external_ref", "ta_record_locator"));
        f.put("status", List.of("reservation_status", "resv_status", "res_status"));
        f.put("arrival", List.of("arrival_date", "trunc_begin_date", "begin_date", "check_in", "checkin"));
        f.put("departure", List.of("departure_date", "trunc_end_date", "end_date", "check_out", "checkout"));
        f.put("original_departure", List.of("original_departure_date", "original_end_date", "booked_departure_date"));
        f.put("actual_check_in", List.of("actual_check_in_date", "actual_arrival_date", "checkin_time"));
        f.put("actual_check_out", List.of("actual_check_out_date", "actual_departure_date", "checkout_time"));
        f.put("nights", List.of("room_nights_total", "no_of_nights", "room_nights", "los"));
        f.put("units", List.of("no_of_rooms", "rooms", "number_of_rooms"));
        f.put("adults", List.of("no_of_adults", "adult"));
        f.put("children", List.of("no_of_children", "child"));
        f.put("booking_date", List.of("insert_date", "created_date", "reservation_date", "book_date"));
        f.put("cancelled_at", List.of("cancellation_date", "cancel_date"));
        f.put("cancellation_reason", List.of("cancellation_reason_code", "cancel_reason"));
        f.put("room_no", List.of("room", "room_number"));
        f.put("room_type", List.of("room_category", "roomtype", "room_type_code"));
        f.put("room_type_charged", List.of("rate_room_type", "rtc", "room_category_label"));
        f.put("rate_code", List.of("rate", "rate_plan", "rate_plan_code"));
        f.put("source_code", List.of("source", "source_of_business"));
        f.put("market_code", List.of("market", "market_segment", "segment"));
        f.put("origin_code", List.of("origin_of_booking", "origin", "origin_code_booking"));
        f.put("channel_code", List.of("channel", "booking_channel", "channel_code_booking"));
        f.put("block_code", List.of("block", "allotment_code", "group_code"));
        f.put("guarantee_code", List.of("guarantee", "reservation_type"));
        f.put("payment_method", List.of("payment_code", "reservation_payment_method", "payment"));
        f.put("complimentary", List.of("complimentary_yn", "comp", "comp_yn"));
        f.put("house_use", List.of("house_use_yn", "house"));
        f.put("pseudo_room", List.of("pseudo_room_yn", "pseudo"));
        f.put("walk_in", List.of("walkin_yn", "walk_in_yn", "walkin", "walkin_flag"));
        f.put("holder_first_name", List.of("guest_first_name", "first_name", "firstname", "first"));
        f.put("holder_last_name", List.of("guest_last_name", "last_name", "surname", "guest_name", "name", "last"));
        f.put("holder_email", List.of("guest_email", "email", "e_mail"));
        f.put("holder_phone", List.of("guest_phone", "phone", "mobile"));
        f.put("holder_nationality", List.of("nationality", "guest_country_code", "guest_country", "country"));
        f.put("holder_pms_profile_id", List.of("guest_name_id", "name_id", "profile_id", "guest_profile_id"));
        f.put("membership_type", List.of("loyalty_member_type", "membership_program", "member_type", "default_member_card_type"));
        f.put("membership_no", List.of("loyalty_member_no", "membership_number", "member_number", "card_number", "loyalty_number", "default_member_card_no"));
        f.put("membership_level", List.of("loyalty_member_level", "member_level", "default_member_card_level"));
        f.put("company_pms_profile_id", List.of("company_name_id", "company_id", "company"));
        f.put("agent_pms_profile_id", List.of("agent_name_id", "travel_agent_id", "travel_agent", "agent"));
        f.put("group_pms_profile_id", List.of("group_name_id", "group_id"));
        f.put("rate_amount", List.of("share_amount", "average_room_rate", "rate_amount", "daily_rate"));
        f.put("room_revenue", List.of("reservation_room_revenue", "room_rev", "lodging_revenue"));
        f.put("fb_revenue", List.of("reservation_fb_revenue", "f_b_revenue", "food_revenue", "fb_rev"));
        f.put("other_revenue", List.of("reservation_other_revenue", "other_rev", "extra_revenue"));
        f.put("tax_total", List.of("tax", "total_tax", "taxes"));
        f.put("total_revenue", List.of("revenue", "total_rev", "reservation_total_revenue"));
        f.put("room_revenue_as_payee", List.of("pr_room_revenue"));
        f.put("currency", List.of("currency_code"));
        f.put("exchange_rate", List.of("exch_rate"));
        f.put("business_date", List.of("export_business_date", "as_of_date"));
        f.put("other_programme", List.of("radisson", "radisson_rewards", "exclusive_flag"));
        return f;
    }

    private record State(Set<String> keys, Map<String, PmsReservation.Status> statusMap) {
    }

    @Override
    public Object newValidationState(LoyaltyImportBatch batch) {
        return new State(new HashSet<>(), statusMap(batch.getOptions()));
    }

    @Override
    public String validate(LoyaltyImportBatch batch, ImportRow row, Object st) {
        State state = (State) st;
        String key = reservationKey(row);
        if (key == null) {
            row.error("pms_reservation_id (RESV_NAME_ID) or confirmation_no is required");
        }
        String rawStatus = row.required("status");
        LocalDate arrival = row.date("arrival");
        LocalDate departure = row.date("departure");
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
        for (String f : List.of("nights", "adults", "children", "confirmation_leg_no")) {
            row.integer(f);
        }
        for (String f : List.of("original_departure", "actual_check_in", "actual_check_out", "booking_date", "cancelled_at", "business_date")) {
            row.date(f);
        }
        for (String f : List.of("rate_amount", "room_revenue", "fb_revenue", "other_revenue", "tax_total", "total_revenue", "room_revenue_as_payee", "exchange_rate")) {
            row.decimal(f);
        }
        for (String f : List.of("complimentary", "house_use", "pseudo_room", "walk_in", "other_programme")) {
            row.bool(f);
        }
        String email = row.text("holder_email");
        if (email != null && LoyaltyNormalizer.email(email) == null) {
            row.error("holder_email is not valid: " + email);
        }
        if (!row.errors().isEmpty()) {
            return null;
        }
        if (!state.keys().add(key)) {
            return "reservation " + key + " appears more than once in the file (sharers need RESV_NAME_ID or a leg number)";
        }
        return null;
    }

    @Override
    public Long apply(LoyaltyImportBatch batch, ImportRow row) {
        PmsReservation.PmsSystem system = pmsSystem(batch.getOptions());
        LocalDate arrival = row.date("arrival");
        LocalDate departure = row.date("departure");
        Integer nights = row.integer("nights");
        BigDecimal room = row.decimal("room_revenue");
        BigDecimal fb = row.decimal("fb_revenue");
        BigDecimal other = row.decimal("other_revenue");
        BigDecimal total = row.decimal("total_revenue");
        if (total == null && (room != null || fb != null || other != null)) {
            total = nz(room).add(nz(fb)).add(nz(other));
        }
        PmsReservation.Status status = mapStatus(row.text("status"), statusMap(batch.getOptions()));
        String membershipType = LoyaltyNormalizer.code(row.text("membership_type"));
        if (membershipType == null && Boolean.TRUE.equals(row.bool("other_programme"))) {
            membershipType = "OTHER";
        }
        PmsReservation in = PmsReservation.builder()
                .tenantId(batch.getTenantId())
                .propertyTenantId(batch.getPropertyTenantId())
                .pmsSystem(system)
                .pmsReservationId(reservationKey(row))
                .confirmationNo(LoyaltyNormalizer.text(row.text("confirmation_no")))
                .confirmationLegNo(row.integer("confirmation_leg_no"))
                .parentPmsReservationId(LoyaltyNormalizer.text(row.text("parent_pms_reservation_id")))
                .externalReference(LoyaltyNormalizer.text(row.text("external_reference")))
                .status(status)
                .statusRaw(LoyaltyNormalizer.text(row.text("status")))
                .arrival(arrival)
                .departure(departure)
                .originalDeparture(row.date("original_departure"))
                .actualCheckIn(stamp(row.date("actual_check_in")))
                .actualCheckOut(stamp(row.date("actual_check_out")))
                .nights(nights != null ? nights : (int) ChronoUnit.DAYS.between(arrival, departure))
                .units(row.integer("units") != null ? row.integer("units") : 1)
                .adults(row.integer("adults"))
                .children(row.integer("children"))
                .bookingDate(row.date("booking_date"))
                .cancelledAt(row.date("cancelled_at"))
                .cancellationReason(LoyaltyNormalizer.text(row.text("cancellation_reason")))
                .roomNo(LoyaltyNormalizer.text(row.text("room_no")))
                .roomType(LoyaltyNormalizer.code(row.text("room_type")))
                .roomTypeCharged(LoyaltyNormalizer.code(row.text("room_type_charged")))
                .rateCode(LoyaltyNormalizer.code(row.text("rate_code")))
                .sourceCode(LoyaltyNormalizer.code(row.text("source_code")))
                .marketCode(LoyaltyNormalizer.code(row.text("market_code")))
                .originCode(LoyaltyNormalizer.code(row.text("origin_code")))
                .channelCode(LoyaltyNormalizer.code(row.text("channel_code")))
                .blockCode(LoyaltyNormalizer.code(row.text("block_code")))
                .guaranteeCode(LoyaltyNormalizer.code(row.text("guarantee_code")))
                .paymentMethod(LoyaltyNormalizer.code(row.text("payment_method")))
                .isDayUse(arrival.equals(departure))
                .isComplimentary(Boolean.TRUE.equals(row.bool("complimentary")))
                .isHouseUse(Boolean.TRUE.equals(row.bool("house_use")))
                .isPseudoRoom(Boolean.TRUE.equals(row.bool("pseudo_room")))
                .isWalkIn(Boolean.TRUE.equals(row.bool("walk_in")))
                .holderFirstName(LoyaltyNormalizer.text(row.text("holder_first_name")))
                .holderLastName(LoyaltyNormalizer.text(row.text("holder_last_name")))
                .holderEmailNorm(LoyaltyNormalizer.email(row.text("holder_email")))
                .holderPhoneNorm(LoyaltyNormalizer.phone(row.text("holder_phone")))
                .holderNationality(country(row.text("holder_nationality")))
                .holderPmsProfileId(LoyaltyNormalizer.text(row.text("holder_pms_profile_id")))
                .membershipType(membershipType)
                .membershipNo(LoyaltyNormalizer.text(row.text("membership_no")))
                .membershipLevel(LoyaltyNormalizer.code(row.text("membership_level")))
                .companyPmsProfileId(LoyaltyNormalizer.text(row.text("company_pms_profile_id")))
                .agentPmsProfileId(LoyaltyNormalizer.text(row.text("agent_pms_profile_id")))
                .groupPmsProfileId(LoyaltyNormalizer.text(row.text("group_pms_profile_id")))
                .rateAmount(row.decimal("rate_amount"))
                .roomRevenue(room)
                .fbRevenue(fb)
                .otherRevenue(other)
                .taxTotal(row.decimal("tax_total"))
                .totalRevenue(total)
                .roomRevenueAsPayee(row.decimal("room_revenue_as_payee"))
                .currency(LoyaltyNormalizer.code(row.text("currency")))
                .exchangeRate(row.decimal("exchange_rate"))
                .revenueFinal(status == PmsReservation.Status.CHECKED_OUT && total != null)
                .raw(rawCopy(row))
                .sourceBusinessDate(row.date("business_date"))
                .build();
        if (!row.errors().isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", row.errors()));
        }
        return reservationService.upsert(in, batch.getId()).getId();
    }

    /** RESV_NAME_ID when the export has it; otherwise confirmation number plus leg, which is unique per hotel too. */
    static String reservationKey(ImportRow row) {
        String id = LoyaltyNormalizer.text(row.text("pms_reservation_id"));
        if (id != null) {
            return id;
        }
        String conf = LoyaltyNormalizer.text(row.text("confirmation_no"));
        if (conf == null) {
            return null;
        }
        Integer leg = row.integer("confirmation_leg_no");
        return leg == null ? "CONF:" + conf : "CONF:" + conf + "/" + leg;
    }

    static PmsReservation.Status mapStatus(String raw, Map<String, PmsReservation.Status> map) {
        if (raw == null) {
            return null;
        }
        return map.get(raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", ""));
    }

    private static Map<String, PmsReservation.Status> statusMap(JsonNode options) {
        Map<String, PmsReservation.Status> map = new java.util.HashMap<>(DEFAULT_STATUS);
        JsonNode custom = options == null ? null : options.get("statusMap");
        if (custom != null && custom.isObject()) {
            custom.fields().forEachRemaining(e -> {
                try {
                    map.put(e.getKey().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", ""),
                            PmsReservation.Status.valueOf(e.getValue().asText().trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("options.statusMap: unknown target status " + e.getValue().asText());
                }
            });
        }
        return map;
    }

    private static PmsReservation.PmsSystem pmsSystem(JsonNode options) {
        String s = options == null ? null : options.path("pmsSystem").asText(options.path("externalSystem").asText(null));
        return s == null || s.isBlank() ? PmsReservation.PmsSystem.OPERA_V5 : PmsReservation.PmsSystem.valueOf(s.trim().toUpperCase(Locale.ROOT));
    }

    private static java.time.OffsetDateTime stamp(LocalDate d) {
        return d == null ? null : d.atStartOfDay(ZoneId.of("Europe/Zagreb")).toOffsetDateTime();
    }

    private static String country(String raw) {
        String c = LoyaltyNormalizer.code(raw);
        return c == null || c.length() != 2 ? null : c;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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

    @SuppressWarnings("unused")
    private static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
