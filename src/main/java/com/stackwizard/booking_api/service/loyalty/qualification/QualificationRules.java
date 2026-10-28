package com.stackwizard.booking_api.service.loyalty.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.LoyaltyChannelMap;
import com.stackwizard.booking_api.model.LoyaltyQualificationStep.RuleCode;
import com.stackwizard.booking_api.model.LoyaltyReservation;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.service.loyalty.LoyaltyNormalizer;
import com.stackwizard.booking_api.service.loyalty.LoyaltyParams;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The built-in rules (Arena Rewards FR-03 / FR-04). New rule kinds are new beans, existing ones are parameterised. */
@Configuration
public class QualificationRules {

    @Bean
    QualificationRule memberMatchRule() {
        return rule(RuleCode.MEMBER_MATCH, (ctx, p) -> ctx.member() != null
                ? QualificationRule.Outcome.pass("matched by " + ctx.reservation().getMatchMethod())
                : QualificationRule.Outcome.fail("no member matches the reservation holder"));
    }

    /**
     * Direct channels only. A code mapped NON-qualifying rejects; a code mapped qualifying passes; when no code of
     * the reservation is mapped, {@code unknown_code_policy} decides. Hotel-specific mappings win over chain-wide.
     */
    @Bean
    QualificationRule channelRule() {
        return rule(RuleCode.CHANNEL, (ctx, p) -> {
            LoyaltyReservation r = ctx.reservation();
            boolean anyQualifying = false;
            for (LoyaltyChannelMap.CodeType type : LoyaltyChannelMap.CodeType.values()) {
                String code = switch (type) {
                    case SOURCE -> LoyaltyNormalizer.code(r.getSourceCode());
                    case MARKET -> LoyaltyNormalizer.code(r.getMarketCode());
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
     * The member must be the holder. Matching already used the holder's card number / e-mail; with
     * {@code require_last_name_match} the holder's surname must also equal the member's.
     */
    @Bean
    QualificationRule holderRule() {
        return rule(RuleCode.HOLDER, (ctx, p) -> {
            if (ctx.member() == null) {
                return QualificationRule.Outcome.fail("no member");
            }
            if (p != null && p.path("require_last_name_match").asBoolean(false)) {
                String holder = fold(ctx.reservation().getHolderLastName());
                String member = fold(ctx.memberLastName());
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

    @Bean
    QualificationRule stayCompletedRule() {
        return rule(RuleCode.STAY_COMPLETED, (ctx, p) -> ctx.reservation().getStatus() == LoyaltyReservation.Status.CHECKED_OUT
                ? QualificationRule.Outcome.pass()
                : QualificationRule.Outcome.fail("status " + ctx.reservation().getStatus()));
    }

    /** Nights credited to another programme (Radisson Rewards) never count twice (FR-13.5). */
    @Bean
    QualificationRule exclusivityRule() {
        return rule(RuleCode.EXCLUSIVITY_FLAG, (ctx, p) -> Boolean.TRUE.equals(ctx.reservation().getExclusivityFlag())
                ? QualificationRule.Outcome.fail("credited to another loyalty programme")
                : QualificationRule.Outcome.pass());
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
