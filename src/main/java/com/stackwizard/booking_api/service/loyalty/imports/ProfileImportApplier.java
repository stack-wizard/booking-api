package com.stackwizard.booking_api.service.loyalty.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.service.crm.CrmContactIdentityService;
import com.stackwizard.booking_api.service.loyalty.LoyaltyNormalizer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PMS guest profiles (OPERA PROFEXPORT and the like): one person per NAME_ID with contact data, the statistics the
 * PMS keeps for the profile (opening state for CRM) and every membership card. Columns MEMBER_CARD_TYPE_n /
 * MEMBER_CARD_NO_n / MEMBER_LEVEL_n / MEMBER_EXPIRATION_n are picked up for any n.
 */
@Component
public class ProfileImportApplier implements ImportApplier, RequiredFields {
    private static final Pattern CARD_COLUMN = Pattern.compile("(?i)^(?:member(?:ship)?[_ ]?card[_ ]?(type|no|number|level|expir\\w*)|member(?:ship)?[_ ]?(type|no|number|level|expir\\w*))[_ ]?(\\d+)$");

    private final CrmContactIdentityService identity;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProfileImportApplier(CrmContactIdentityService identity) {
        this.identity = identity;
    }

    @Override
    public LoyaltyImportBatch.ImportType type() {
        return LoyaltyImportBatch.ImportType.PROFILES;
    }

    @Override
    public List<String> requiredFields() {
        return List.of("pms_profile_id", "last_name");
    }

    @Override
    public Map<String, List<String>> fields() {
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("pms_profile_id", List.of("name_id", "profile_id", "guest_name_id", "opera_profile_id"));
        f.put("first_name", List.of("firstname", "first", "ime"));
        f.put("last_name", List.of("lastname", "surname", "name", "prezime"));
        f.put("email", List.of("e_mail", "email_address", "mail"));
        f.put("phone", List.of("mobile", "mobile_phone", "phone_number", "telephone"));
        f.put("country", List.of("country_code", "country_id"));
        f.put("nationality", List.of("nationality_code"));
        f.put("city", List.of("town"));
        f.put("postal_code", List.of("zip", "zip_code", "postcode"));
        f.put("address", List.of("address1", "address_line", "street"));
        f.put("date_of_birth", List.of("birth_date", "dob", "birthday"));
        f.put("gender", List.of("sex"));
        f.put("language", List.of("lang", "language_code"));
        f.put("vip", List.of("vip_status", "vip_code"));
        f.put("inactive_date", List.of("inactive", "inactive_yn"));
        f.put("merged_into", List.of("orig_name_id", "merged_into_name_id"));
        f.put("first_stay", List.of("first_stay_date", "first_arrival"));
        f.put("last_stay", List.of("last_stay_date", "last_departure"));
        f.put("next_arrival", List.of("next_stay", "next_arrival_date"));
        f.put("total_stays", List.of("total_arrivals", "stays", "no_of_stays"));
        f.put("total_nights", List.of("nights", "room_nights", "no_of_nights"));
        f.put("total_revenue", List.of("revenue", "total_room_revenue", "lifetime_revenue"));
        f.put("revenue_ytd", List.of("room_revenue_ytd", "ytd_revenue"));
        f.put("revenue_last_year", List.of("room_revenue_last_year", "last_year_revenue"));
        f.put("total_cancellations", List.of("cancellations", "total_cancellation"));
        f.put("total_no_shows", List.of("no_shows"));
        f.put("consent_email", List.of("newsletter", "email_consent", "marketing_email", "mailing_yn"));
        f.put("consent_sms", List.of("sms_consent", "marketing_sms"));
        return f;
    }

    @Override
    public Object newValidationState(LoyaltyImportBatch batch) {
        return new HashSet<String>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String validate(LoyaltyImportBatch batch, ImportRow row, Object st) {
        Set<String> seen = (Set<String>) st;
        String id = row.required("pms_profile_id");
        row.required("last_name");
        String email = row.text("email");
        if (email != null && LoyaltyNormalizer.email(email) == null) {
            row.error("email is not valid: " + email);
        }
        for (String f : List.of("date_of_birth", "first_stay", "last_stay", "next_arrival", "inactive_date")) {
            row.date(f);
        }
        for (String f : List.of("total_stays", "total_nights", "total_cancellations", "total_no_shows")) {
            row.integer(f);
        }
        for (String f : List.of("total_revenue", "revenue_ytd", "revenue_last_year")) {
            row.decimal(f);
        }
        row.bool("consent_email");
        row.bool("consent_sms");
        if (!row.errors().isEmpty()) {
            return null;
        }
        if (!seen.add(id)) {
            return "profile " + id + " appears more than once in the file";
        }
        return null;
    }

    @Override
    public Long apply(LoyaltyImportBatch batch, ImportRow row) {
        LocalDate asOf = batch.getOptions().hasNonNull("asOf") ? row.parseDate(batch.getOptions().get("asOf").asText()) : null;
        BigDecimal revenue = row.decimal("total_revenue");
        if (revenue == null && (row.decimal("revenue_ytd") != null || row.decimal("revenue_last_year") != null)) {
            revenue = nz(row.decimal("revenue_ytd")).add(nz(row.decimal("revenue_last_year")));
        }
        ObjectNode stats = objectMapper.createObjectNode();
        putDate(stats, "firstStay", row.date("first_stay"));
        putDate(stats, "lastStay", row.date("last_stay"));
        putDate(stats, "nextArrival", row.date("next_arrival"));
        putInt(stats, "totalStays", row.integer("total_stays"));
        putInt(stats, "totalNights", row.integer("total_nights"));
        putInt(stats, "totalCancellations", row.integer("total_cancellations"));
        putInt(stats, "totalNoShows", row.integer("total_no_shows"));
        putDec(stats, "revenueYtd", row.decimal("revenue_ytd"));
        putDec(stats, "revenueLastYear", row.decimal("revenue_last_year"));
        Map<String, Boolean> consents = new LinkedHashMap<>();
        if (row.has("consent_email")) consents.put("EMAIL", row.bool("consent_email"));
        if (row.has("consent_sms")) consents.put("SMS", row.bool("consent_sms"));
        boolean inactive = row.has("inactive_date") && (row.date("inactive_date") != null || Boolean.TRUE.equals(row.bool("inactive_date")));
        CrmContactIdentityService.GuestInput guest = new CrmContactIdentityService.GuestInput(
                row.text("first_name"), row.text("last_name"), row.text("email"), row.text("phone"), code2(row.text("country")),
                row.text("city"), row.text("postal_code"), row.text("address"), row.date("date_of_birth"), row.text("gender"),
                row.text("language"), code2(row.text("nationality")), row.text("vip"));
        CrmContactIdentityService.PmsProfile profile = new CrmContactIdentityService.PmsProfile(
                row.text("pms_profile_id"), guest, memberships(row), consents,
                row.integer("total_stays"), row.integer("total_nights"), revenue, asOf, stats, inactive, row.text("merged_into"));
        if (!row.errors().isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", row.errors()));
        }
        return identity.upsertFromPms(batch.getTenantId(), profile).getId();
    }

    /** Membership columns are numbered (card 1..10 in PROFEXPORT); group them by number. */
    static List<CrmContactIdentityService.PmsMembership> memberships(ImportRow row) {
        Map<String, Map<String, String>> byIndex = new LinkedHashMap<>();
        JsonNode raw = row.raw();
        Iterator<String> names = raw.fieldNames();
        while (names.hasNext()) {
            String header = names.next();
            Matcher m = CARD_COLUMN.matcher(header.trim().replace(' ', '_'));
            if (!m.matches()) {
                continue;
            }
            String kind = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase();
            String value = raw.path(header).asText("").trim();
            if (value.isEmpty()) {
                continue;
            }
            Map<String, String> card = byIndex.computeIfAbsent(m.group(3), k -> new LinkedHashMap<>());
            if (kind.startsWith("type")) card.put("type", value);
            else if (kind.startsWith("no") || kind.startsWith("number")) card.put("no", value);
            else if (kind.startsWith("level")) card.put("level", value);
            else card.put("expires", value);
        }
        List<CrmContactIdentityService.PmsMembership> out = new ArrayList<>();
        boolean first = true;
        for (Map<String, String> card : byIndex.values()) {
            if (card.get("no") == null) {
                continue;
            }
            String type = card.getOrDefault("type", "UNKNOWN");
            LocalDate expires = card.get("expires") == null ? null : row.parseDate(card.get("expires"));
            out.add(new CrmContactIdentityService.PmsMembership(type, card.get("no"), card.get("level"), first, expires));
            first = false;
        }
        return out;
    }

    private static String code2(String raw) {
        String c = LoyaltyNormalizer.code(raw);
        return c == null || c.length() != 2 ? null : c;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void putDate(ObjectNode n, String k, LocalDate v) {
        if (v != null) n.put(k, v.toString());
    }

    private static void putInt(ObjectNode n, String k, Integer v) {
        if (v != null) n.put(k, v);
    }

    private static void putDec(ObjectNode n, String k, BigDecimal v) {
        if (v != null) n.put(k, v);
    }
}
