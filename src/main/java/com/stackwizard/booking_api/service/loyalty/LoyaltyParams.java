package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;

/**
 * Typed view of {@code loyalty_rule_set.params}. Defaults reproduce the Arena Rewards rules of 2026, but every
 * value is a parameter so another client (or a later rule version) changes data, not code.
 */
public record LoyaltyParams(
        int validityMonths,
        int nightsPerDate,
        boolean bonusCountsForTier,
        boolean openingBalanceCountsForTier,
        int walletBonusNights,
        int referralBonusNights,
        int claimWindowDays,
        int nearNextThreshold,
        String unknownCodePolicy,
        int downgradeStep
) {
    public static final String UNKNOWN_QUALIFYING = "QUALIFYING";
    public static final String UNKNOWN_NON_QUALIFYING = "NON_QUALIFYING";

    public static LoyaltyParams of(LoyaltyRuleSet ruleSet) {
        return of(ruleSet == null ? null : ruleSet.getParams());
    }

    public static LoyaltyParams of(JsonNode p) {
        return new LoyaltyParams(
                intOr(p, "validity_months", 24),
                intOr(p, "nights_per_date", 1),
                boolOr(p, "bonus_counts_for_tier", true),
                boolOr(p, "opening_balance_counts_for_tier", true),
                intOr(p, "wallet_bonus_nights", 2),
                intOr(p, "referral_bonus_nights", 2),
                intOr(p, "claim_window_days", 60),
                intOr(p, "near_next_threshold", 3),
                textOr(p, "unknown_code_policy", UNKNOWN_QUALIFYING),
                intOr(p, "downgrade_step", 1)
        );
    }

    private static int intOr(JsonNode p, String key, int fallback) {
        return p != null && p.hasNonNull(key) && p.get(key).canConvertToInt() ? p.get(key).asInt() : fallback;
    }

    private static boolean boolOr(JsonNode p, String key, boolean fallback) {
        return p != null && p.hasNonNull(key) ? p.get(key).asBoolean(fallback) : fallback;
    }

    private static String textOr(JsonNode p, String key, String fallback) {
        return p != null && p.hasNonNull(key) && !p.get(key).asText().isBlank() ? p.get(key).asText().trim() : fallback;
    }
}
