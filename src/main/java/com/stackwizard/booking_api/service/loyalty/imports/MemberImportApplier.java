package com.stackwizard.booking_api.service.loyalty.imports;

import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import com.stackwizard.booking_api.model.LoyaltyMemberIdentifier;
import com.stackwizard.booking_api.model.LoyaltyProgram;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;
import com.stackwizard.booking_api.repository.LoyaltyMemberIdentifierRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyProgramRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierRepository;
import com.stackwizard.booking_api.service.loyalty.LoyaltyMemberImportSupport;
import com.stackwizard.booking_api.service.loyalty.LoyaltyNormalizer;
import com.stackwizard.booking_api.service.loyalty.LoyaltyProgramService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Members with their opening state (migration from the previous loyalty platform). Re-importable: rows match by
 * member number, the opening balance is replaced (old one reversed, new one posted), the tier from the file is
 * kept as the assigned tier until it expires or the member out-earns it.
 */
@Component
public class MemberImportApplier implements ImportApplier, RequiredFields {
    private final LoyaltyProgramService programService;
    private final LoyaltyProgramRepository programRepo;
    private final LoyaltyMemberRepository memberRepo;
    private final LoyaltyMemberIdentifierRepository identifierRepo;
    private final LoyaltyTierRepository tierRepo;
    private final LoyaltyMemberImportSupport support;
    private final Clock clock;

    public MemberImportApplier(LoyaltyProgramService programService,
                               LoyaltyProgramRepository programRepo,
                               LoyaltyMemberRepository memberRepo,
                               LoyaltyMemberIdentifierRepository identifierRepo,
                               LoyaltyTierRepository tierRepo,
                               LoyaltyMemberImportSupport support,
                               Clock loyaltyClock) {
        this.programService = programService;
        this.programRepo = programRepo;
        this.memberRepo = memberRepo;
        this.identifierRepo = identifierRepo;
        this.tierRepo = tierRepo;
        this.support = support;
        this.clock = loyaltyClock;
    }

    @Override
    public LoyaltyImportBatch.ImportType type() {
        return LoyaltyImportBatch.ImportType.MEMBERS;
    }

    @Override
    public List<String> requiredFields() {
        return List.of("member_number", "first_name", "last_name", "email");
    }

    @Override
    public Map<String, List<String>> fields() {
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("member_number", List.of("card_number", "membership_number", "membership_no", "member_id", "digital_card_number", "broj_kartice", "card"));
        f.put("first_name", List.of("firstname", "name", "ime"));
        f.put("last_name", List.of("lastname", "surname", "prezime"));
        f.put("email", List.of("e_mail", "mail"));
        f.put("tier", List.of("level", "status", "current_level", "razina"));
        f.put("tier_valid_until", List.of("status_valid_until", "valid_until", "tier_expiry", "expiry_date"));
        f.put("qualified_nights_total", List.of("realized_room_nights", "room_nights", "nights", "total_nights", "nocenja"));
        f.put("nights_current_year", List.of("nights_this_year", "current_year_nights"));
        f.put("last_checkout", List.of("last_stay", "last_check_out", "last_departure"));
        f.put("activated_at", List.of("activation_date", "registration_date", "registered_at", "created"));
        f.put("wallet_added", List.of("wallet", "wallet_added_at", "card_in_wallet"));
        f.put("phone", List.of("mobile", "mobile_phone", "mobile_phone_number", "telephone", "phone_number"));
        f.put("country", List.of("drzava", "country_code"));
        f.put("city", List.of("grad"));
        f.put("postal_code", List.of("zip", "postcode", "postanski_broj"));
        f.put("address", List.of("street", "adresa"));
        f.put("date_of_birth", List.of("birth_date", "dob", "birthday", "datum_rodjenja"));
        f.put("gender", List.of("sex", "spol"));
        f.put("language", List.of("lang", "languages", "jezik"));
        f.put("referrer_number", List.of("referral_membership_card_number", "referral", "referrer"));
        f.put("consent_email", List.of("newsletter", "email_consent", "marketing_email"));
        f.put("consent_sms", List.of("sms_consent", "marketing_sms"));
        f.put("consent_push", List.of("push_consent"));
        f.put("interests", List.of("interest", "interesi"));
        f.put("member_status", List.of("account_status", "active"));
        return f;
    }

    private static final class State {
        final Set<String> numbers = new HashSet<>();
        final Set<String> emails = new HashSet<>();
        Set<String> tierCodes;
    }

    @Override
    public Object newValidationState(LoyaltyImportBatch batch) {
        State s = new State();
        LoyaltyRuleSet rs = programService.ruleSetOn(batch.getProgramId(), LocalDate.now(clock));
        s.tierCodes = new HashSet<>();
        for (LoyaltyTier t : tierRepo.findByRuleSetIdOrderByRankAsc(rs.getId())) {
            s.tierCodes.add(t.getCode().toUpperCase(Locale.ROOT));
            s.tierCodes.add(t.getName().toUpperCase(Locale.ROOT));
        }
        return s;
    }

    @Override
    public String validate(LoyaltyImportBatch batch, ImportRow row, Object st) {
        State state = (State) st;
        String number = row.required("member_number");
        row.required("first_name");
        row.required("last_name");
        String rawEmail = row.required("email");
        String email = LoyaltyNormalizer.email(rawEmail);
        if (rawEmail != null && email == null) {
            row.error("email is not valid: " + rawEmail);
        }
        String tier = row.text("tier");
        if (tier != null && !state.tierCodes.contains(tier.toUpperCase(Locale.ROOT))) {
            row.error("unknown tier " + tier + " (known: " + String.join(", ", state.tierCodes) + ")");
        }
        Integer total = row.integer("qualified_nights_total");
        Integer year = row.integer("nights_current_year");
        if (total != null && total < 0) {
            row.error("qualified_nights_total cannot be negative");
        }
        if (total != null && year != null && year > total) {
            row.error("nights_current_year is larger than qualified_nights_total");
        }
        row.date("tier_valid_until");
        row.date("last_checkout");
        row.date("activated_at");
        row.date("date_of_birth");
        row.bool("wallet_added");
        row.bool("consent_email");
        row.bool("consent_sms");
        row.bool("consent_push");
        if (!row.errors().isEmpty()) {
            return null;
        }
        if (!state.numbers.add(number)) {
            return "member_number " + number + " appears more than once in the file";
        }
        if (!state.emails.add(email)) {
            return "email " + email + " appears more than once in the file";
        }
        // an e-mail already used by a different member number is a conflict to resolve by hand (FR-01.7)
        identifierRepo.findByTenantIdAndTypeAndValueNorm(batch.getTenantId(), LoyaltyMemberIdentifier.Type.EMAIL, email)
                .flatMap(i -> memberRepo.findById(i.getMemberId()))
                .filter(m -> !m.getMemberNumber().equals(number))
                .ifPresent(m -> row.error("email already belongs to member " + m.getMemberNumber()));
        return null;
    }

    @Override
    public Long apply(LoyaltyImportBatch batch, ImportRow row) {
        boolean suppress = batch.getOptions().path("suppressEvents").asBoolean(true);
        LocalDate today = LocalDate.now(clock);
        LocalDate asOf = batch.getOptions().hasNonNull("asOf") ? row.parseDate(batch.getOptions().get("asOf").asText()) : today;
        if (asOf == null) {
            asOf = today;
        }
        String status = row.text("member_status");
        LoyaltyMemberImportSupport.ImportedMember in = new LoyaltyMemberImportSupport.ImportedMember(
                row.text("member_number"),
                row.text("first_name"),
                row.text("last_name"),
                LoyaltyNormalizer.email(row.text("email")),
                row.text("phone"),
                row.text("country"),
                row.text("city"),
                row.text("postal_code"),
                row.text("address"),
                row.date("date_of_birth"),
                row.text("gender"),
                row.text("language"),
                row.text("referrer_number"),
                consents(row),
                interests(row.text("interests")),
                row.date("activated_at"),
                Boolean.TRUE.equals(row.bool("wallet_added")) ? walletDate(row) : null,
                row.text("tier"),
                row.date("tier_valid_until"),
                row.integer("qualified_nights_total"),
                row.integer("nights_current_year"),
                row.date("last_checkout"),
                status != null && (status.equalsIgnoreCase("inactive") || status.equalsIgnoreCase("deactivated") || status.equalsIgnoreCase("false") || status.equals("0")),
                asOf,
                batch.getId());
        if (!row.errors().isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", row.errors()));
        }
        return support.importMember(batch.getTenantId(), batch.getProgramId(), in, suppress);
    }

    @Override
    public void afterApply(LoyaltyImportBatch batch) {
        // keep the running number above every imported card number so new sign-ups never collide
        LoyaltyProgram program = programRepo.lockById(batch.getProgramId()).orElseThrow();
        String prefix = program.getMemberNumberPrefix() == null ? "" : program.getMemberNumberPrefix();
        memberRepo.maxMemberNumber(program.getId()).ifPresent(max -> {
            String digits = max.startsWith(prefix) ? max.substring(prefix.length()) : max;
            try {
                long n = Long.parseLong(digits);
                if (n >= program.getMemberNumberNext()) {
                    program.setMemberNumberNext(n + 1);
                    programRepo.save(program);
                }
            } catch (NumberFormatException ignored) {
                // non-numeric legacy numbers: the sequence skips taken numbers anyway
            }
        });
    }

    private java.time.OffsetDateTime walletDate(ImportRow row) {
        LocalDate d = row.parseDate(row.text("wallet_added"));
        LocalDate when = d != null ? d : LocalDate.now(clock);
        return when.atStartOfDay(ZoneId.of("Europe/Zagreb")).toOffsetDateTime();
    }

    private static Map<String, Boolean> consents(ImportRow row) {
        Map<String, Boolean> c = new LinkedHashMap<>();
        if (row.has("consent_email")) {
            c.put("EMAIL", row.bool("consent_email"));
        }
        if (row.has("consent_sms")) {
            c.put("SMS", row.bool("consent_sms"));
        }
        if (row.has("consent_push")) {
            c.put("PUSH", row.bool("consent_push"));
        }
        return c;
    }

    private static List<String> interests(String raw) {
        if (raw == null) {
            return null;
        }
        return java.util.Arrays.stream(raw.split("[|,;]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }




}
