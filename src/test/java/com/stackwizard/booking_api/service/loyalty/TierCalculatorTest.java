package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TierCalculatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final List<LoyaltyTier> ARENA = List.of(
            tier(1L, "LIGHT", 0, 0), tier(2L, "SILVER", 1, 10), tier(3L, "GOLD", 2, 31), tier(4L, "PLATINUM", 3, 61));
    private static final LoyaltyParams DEFAULTS = LoyaltyParams.of(new ObjectMapper().createObjectNode());

    @Test
    void thresholdsMatchArenaTable() {
        assertThat(code(0)).isEqualTo("LIGHT");
        assertThat(code(9)).isEqualTo("LIGHT");
        assertThat(code(10)).isEqualTo("SILVER");
        assertThat(code(30)).isEqualTo("SILVER");
        assertThat(code(31)).isEqualTo("GOLD");
        assertThat(code(60)).isEqualTo("GOLD");
        assertThat(code(61)).isEqualTo("PLATINUM");
        assertThat(code(500)).isEqualTo("PLATINUM");
    }

    @Test
    void entryTierWhenBelowLowestThreshold() {
        List<LoyaltyTier> tiers = List.of(tier(1L, "LIGHT", 0, 1), tier(2L, "SILVER", 1, 10));
        assertThat(TierCalculator.tierFor(tiers, 0).orElseThrow().getCode()).isEqualTo("LIGHT");
    }

    @Test
    void countsYearAnd24MonthsAndRespectsCountFrom() {
        List<LoyaltyLedgerEntry> entries = List.of(
                stay(LocalDate.of(2026, 3, 1)),
                stay(LocalDate.of(2025, 12, 31)),
                stay(LocalDate.of(2023, 1, 1)),
                opening(20, LocalDate.of(2025, 12, 31)),
                bonus(2, LocalDate.of(2026, 5, 1)));
        TierCalculator.Counters c = TierCalculator.count(entries, DEFAULTS, null, TODAY);
        assertThat(c.total()).isEqualTo(25);
        assertThat(c.currentYear()).isEqualTo(3);
        assertThat(c.last24Months()).isEqualTo(24);
        assertThat(c.tierNights()).isEqualTo(25);

        TierCalculator.Counters fresh = TierCalculator.count(entries, DEFAULTS, LocalDate.of(2026, 1, 1), TODAY);
        assertThat(fresh.tierNights()).isEqualTo(3);
        assertThat(fresh.total()).isEqualTo(25);
    }

    @Test
    void bonusAndOpeningBalanceCanBeExcludedFromTier() {
        var params = LoyaltyParams.of(new ObjectMapper().createObjectNode()
                .put("bonus_counts_for_tier", false).put("opening_balance_counts_for_tier", false));
        List<LoyaltyLedgerEntry> entries = List.of(stay(LocalDate.of(2026, 3, 1)), opening(20, LocalDate.of(2025, 12, 31)), bonus(2, LocalDate.of(2026, 5, 1)));
        assertThat(TierCalculator.count(entries, params, null, TODAY).tierNights()).isEqualTo(1);
    }

    @Test
    void rollingValidityIsTwoYearsFromLastCheckout() {
        assertThat(TierCalculator.validUntil(LoyaltyRuleSet.TierPolicy.ROLLING_VALIDITY, DEFAULTS, LocalDate.of(2026, 8, 15), TODAY))
                .isEqualTo(LocalDate.of(2028, 8, 15));
        assertThat(TierCalculator.validUntil(LoyaltyRuleSet.TierPolicy.ROLLING_VALIDITY, DEFAULTS, null, TODAY)).isNull();
        assertThat(TierCalculator.validUntil(LoyaltyRuleSet.TierPolicy.ANNUAL_CYCLE, DEFAULTS, null, TODAY))
                .isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void expiryDropsToEarnedTierOrOneStepUnderAnnualCycle() {
        LoyaltyTier platinum = ARENA.get(3);
        assertThat(TierCalculator.afterExpiry(LoyaltyRuleSet.TierPolicy.ROLLING_VALIDITY, DEFAULTS, ARENA, platinum, 0).orElseThrow().getCode())
                .isEqualTo("LIGHT");
        assertThat(TierCalculator.afterExpiry(LoyaltyRuleSet.TierPolicy.ANNUAL_CYCLE, DEFAULTS, ARENA, platinum, 0).orElseThrow().getCode())
                .isEqualTo("GOLD");
        assertThat(TierCalculator.afterExpiry(LoyaltyRuleSet.TierPolicy.ANNUAL_CYCLE, DEFAULTS, ARENA, platinum, 70).orElseThrow().getCode())
                .isEqualTo("PLATINUM");
    }

    @Test
    void nextTier() {
        assertThat(TierCalculator.nextTier(ARENA, ARENA.get(1)).orElseThrow().getCode()).isEqualTo("GOLD");
        assertThat(TierCalculator.nextTier(ARENA, ARENA.get(3))).isEmpty();
    }

    private static String code(int nights) {
        return TierCalculator.tierFor(ARENA, nights).orElseThrow().getCode();
    }

    private static LoyaltyTier tier(Long id, String code, int rank, int threshold) {
        return LoyaltyTier.builder().id(id).code(code).name(code).rank(rank).threshold(threshold).build();
    }

    private static LoyaltyLedgerEntry stay(LocalDate night) {
        return LoyaltyLedgerEntry.builder().metric(LoyaltyLedgerEntry.Metric.QUALIFYING_NIGHT).qty(1).nightDate(night).asOf(night)
                .source(LoyaltyLedgerEntry.Source.STAY).active(true).build();
    }

    private static LoyaltyLedgerEntry opening(int qty, LocalDate asOf) {
        return LoyaltyLedgerEntry.builder().metric(LoyaltyLedgerEntry.Metric.QUALIFYING_NIGHT).qty(qty).asOf(asOf)
                .source(LoyaltyLedgerEntry.Source.OPENING_BALANCE).active(true).build();
    }

    private static LoyaltyLedgerEntry bonus(int qty, LocalDate asOf) {
        return LoyaltyLedgerEntry.builder().metric(LoyaltyLedgerEntry.Metric.BONUS_NIGHT).qty(qty).asOf(asOf)
                .source(LoyaltyLedgerEntry.Source.WALLET).active(true).build();
    }
}
