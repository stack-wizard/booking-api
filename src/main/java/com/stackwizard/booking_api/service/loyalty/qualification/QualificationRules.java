package com.stackwizard.booking_api.service.loyalty.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.LoyaltyChannelMap;
import com.stackwizard.booking_api.model.LoyaltyQualificationStep.RuleCode;
import com.stackwizard.booking_api.model.PmsReservation;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.service.loyalty.LoyaltyNormalizer;
import com.stackwizard.booking_api.service.loyalty.LoyaltyParams;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The built-in rules (Arena Rewards FR-03 / FR-04). New rule kinds are new beans, existing ones are parameterised. */
@Configuration
public class QualificationRules {

    /** The matched person must hold a membership in this programme. */
    @Bean
    QualificationRule memberMatchRule() {
        return rule(RuleCode.MEMBER_MATCH, (ctx, p) -> {
            if (ctx.contact() == null) {
                return QualificationRule.Outcome.fail("no person matches the reservation holder");
            }
            if (ctx.member() == null) {
                return QualificationRule.Outcome.fail("holder is known but not a member");
            }
            return QualificationRule.Outcome.pass("matched by " + ctx.reservation().getMatchMethod());
        });
    }

    /**
     * Direct channels only. A code mapped NON-qualifying rejects; a code mapped qualifying passes; when no code of
     * the reservation is mapped, {@code unknown_code_policy} decides. Hotel-specific mappings win over chain-wide.
     */
    @Bean
    QualificationRule channelRule() {
        return rule(RuleCode.CHANNEL, (ctx, p) -> {
            PmsReservation r = ctx.reservation();
            boolean anyQualifying = false;
            for (LoyaltyChannelMap.CodeType type : LoyaltyChannelMap.CodeType.values()) {
                String code = switch (type) {
                    case SOURCE -> LoyaltyNormalizer.code(r.getSourceCode());
                    case MARKET -> LoyaltyNormalizer.code(r.getMarketCode());
                    case ORIGIN -> LoyaltyNormalizer.code(r.getOriginCode());
                    case CHANNEL -> LoyaltyNormalizer.code(r.getChannelCode());
                };
                if (code == null) {
                    continue;
                }
                Boolean q = lookup(ctx.channelMap(), r.getPropertyTenantId(), type, code);
                if (Boolean.FALSE.equals(q)) {
                    return QualificationRule.Outcome.fail(type + " " + code + " is not a direct channel");
                }
                if (Boolean.TRUE.equals(q)) {
                    anyQualifying = true;
                }
            }
            if (anyQualifying) {
                return QualificationRule.Outcome.pass();
            }
            return LoyaltyParams.UNKNOWN_NON_QUALIFYING.equalsIgnoreCase(ctx.params().unknownCodePolicy())
                    ? QualificationRule.Outcome.fail("channel codes are not mapped")
                    : QualificationRule.Outcome.pass("channel codes not mapped; unknown_code_policy=QUALIFYING");
        });
    }

    /**
     * The member must be the holder (FR-04.4). The match already used the holder's profile / card / e-mail; this
     * guards against a reservation that names another PMS profile, and optionally checks the surname.
     */
    @Bean
    QualificationRule holderRule() {
        return rule(RuleCode.HOLDER, (ctx, p) -> {
            if (ctx.contact() == null) {
                return QualificationRule.Outcome.fail("no person");
            }
            String holderProfile = LoyaltyNormalizer.text(ctx.reservation().getHolderPmsProfileId());
            String contactProfile = LoyaltyNormalizer.text(ctx.contact().getOperaProfileId());
            if (holderProfile != null && contactProfile != null && !holderProfile.equals(contactProfile)) {
                return QualificationRule.Outcome.fail("reservation holder is PMS profile " + holderProfile + ", member is " + contactProfile);
            }
            if (p != null && p.path("require_last_name_match").asBoolean(false)) {
                String holder = fold(ctx.reservation().getHolderLastName());
                String member = fold(ctx.contact().getLastName());
                if (holder == null || !holder.equals(member)) {
                    return QualificationRule.Outcome.fail("holder surname differs from member");
                }
            }
            return QualificationRule.Outcome.pass();
        });
    }

    /** No retroactivity (FR-01.2): only nights on or after the activation date count. */
    @Bean
    QualificationRule activationRule() {
        return rule(RuleCode.ACTIVATION, (ctx, p) -> {
            if (ctx.nights().isEmpty()) {
                return QualificationRule.Outcome.pass("no nights to check (day use)");
            }
            LocalDate activated = ctx.member().getActivatedAt().atZoneSameInstant(ZoneId.of("Europe/Zagreb")).toLocalDate();
            List<LocalDate> kept = ctx.nights().stream().filter(d -> !d.isBefore(activated)).toList();
            if (kept.isEmpty()) {
                return QualificationRule.Outcome.fail("stay before activation " + activated);
            }
            String detail = kept.size() < ctx.nights().size() ? (ctx.nights().size() - kept.size()) + " night(s) before activation" : null;
            ctx.nights(kept);
            return QualificationRule.Outcome.pass(detail);
        });
    }

    /** Card in Apple/Google Wallet is a condition for collecting nights (FR-06), unless an operator recorded an exception. */
    @Bean
    QualificationRule walletRule() {
        return rule(RuleCode.WALLET, (ctx, p) -> {
            if (ctx.member().getWalletAddedAt() != null) {
                return QualificationRule.Outcome.pass();
            }
            if (ctx.member().getWalletExceptionReason() != null) {
                return QualificationRule.Outcome.pass("wallet exception: " + ctx.member().getWalletExceptionReason());
            }
            return QualificationRule.Outcome.fail("membership card not in wallet");
        });
    }

    /**
     * A real, finished stay: checked out, not a day use (0 nights), and not complimentary / house use unless the
     * rule's params say {@code allow_complimentary}. Pseudo (posting-master) rooms never count.
     */
    @Bean
    QualificationRule stayCompletedRule() {
        return rule(RuleCode.STAY_COMPLETED, (ctx, p) -> {
            PmsReservation r = ctx.reservation();
            if (r.getStatus() != PmsReservation.Status.CHECKED_OUT) {
                return QualificationRule.Outcome.fail("status " + r.getStatus());
            }
            if (Boolean.TRUE.equals(r.getIsDayUse()) || ctx.nights().isEmpty()) {
                return QualificationRule.Outcome.fail("day use (no nights)");
            }
            if (Boolean.TRUE.equals(r.getIsPseudoRoom())) {
                return QualificationRule.Outcome.fail("posting-master / pseudo room");
            }
            boolean allowComp = p != null && p.path("allow_complimentary").asBoolean(false);
            if (!allowComp && (Boolean.TRUE.equals(r.getIsComplimentary()) || Boolean.TRUE.equals(r.getIsHouseUse()))) {
                return QualificationRule.Outcome.fail(Boolean.TRUE.equals(r.getIsHouseUse()) ? "house use" : "complimentary stay");
            }
            return QualificationRule.Outcome.pass();
        });
    }

    /**
     * Nights credited to another programme never count twice (FR-13.5). The PMS reports which membership the
     * reservation is attached to; anything that is not this programme (or a code in {@code own_program_codes}) rejects.
     */
    @Bean
    QualificationRule exclusivityRule() {
        return rule(RuleCode.EXCLUSIVITY_FLAG, (ctx, p) -> {
            String type = LoyaltyNormalizer.code(ctx.reservation().getMembershipType());
            if (type == null) {
                return QualificationRule.Outcome.pass();
            }
            List<String> own = new ArrayList<>();
            own.add(LoyaltyNormalizer.code(ctx.program().getCode()));
            if (p != null && p.has("own_program_codes")) {
                p.get("own_program_codes").forEach(n -> own.add(LoyaltyNormalizer.code(n.asText())));
            }
            return own.contains(type)
                    ? QualificationRule.Outcome.pass()
                    : QualificationRule.Outcome.fail("credited to programme " + type);
        });
    }

    @Bean
    QualificationRule uniquePerDateRule(LoyaltyLedgerRepository ledgerRepo) {
        return rule(RuleCode.UNIQUE_PER_DATE, (ctx, p) -> {
            List<LocalDate> kept = ctx.nights().stream()
                    .filter(d -> !ledgerRepo.nightCreditedElsewhere(ctx.member().getId(), d, ctx.reservation().getId()))
                    .toList();
            if (kept.isEmpty()) {
                return QualificationRule.Outcome.fail("every night is already credited");
            }
            String detail = kept.size() < ctx.nights().size() ? (ctx.nights().size() - kept.size()) + " night(s) already credited" : null;
            ctx.nights(kept);
            return QualificationRule.Outcome.pass(detail);
        });
    }

    static Boolean lookup(List<LoyaltyChannelMap> map, Long propertyTenantId, LoyaltyChannelMap.CodeType type, String code) {
        Boolean chainWide = null;
        for (LoyaltyChannelMap m : map) {
            if (m.getCodeType() != type || !m.getCode().equalsIgnoreCase(code)) {
                continue;
            }
            if (m.getPropertyTenantId() == null) {
                chainWide = m.getQualifying();
            } else if (Objects.equals(m.getPropertyTenantId(), propertyTenantId)) {
                return m.getQualifying();
            }
        }
        return chainWide;
    }

    private static String fold(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return java.text.Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static QualificationRule rule(RuleCode code, java.util.function.BiFunction<QualificationContext, JsonNode, QualificationRule.Outcome> fn) {
        return new QualificationRule() {
            @Override
            public RuleCode code() {
                return code;
            }

            @Override
            public Outcome check(QualificationContext ctx, JsonNode params) {
                return fn.apply(ctx, params);
            }
        };
    }
}
