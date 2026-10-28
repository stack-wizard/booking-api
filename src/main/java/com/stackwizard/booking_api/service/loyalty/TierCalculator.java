package com.stackwizard.booking_api.service.loyalty;

import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Pure tier arithmetic (no I/O), shared by the engine, the expiry job and tests. */
public final class TierCalculator {
    private TierCalculator() {
    }

    public record Counters(int tierNights, int total, int currentYear, int last24Months) {
    }

    /**
     * Counts nights from the ledger. {@code tierNights} honours the rule set: bonus / opening balance may be
     * excluded, and nights before {@code countFrom} (an expired status) no longer count toward the tier.
     */
    public static Counters count(List<LoyaltyLedgerEntry> activeEntries, LoyaltyParams params, LocalDate countFrom, LocalDate today) {
        int tier = 0;
        int total = 0;
        int year = 0;
        int last24 = 0;
        LocalDate from24 = today.minusMonths(24);
        for (LoyaltyLedgerEntry e : activeEntries) {
            if (e.getMetric() == LoyaltyLedgerEntry.Metric.POINTS) {
                continue;
            }
            int qty = e.getQty();
            total += qty;
            LocalDate when = e.getNightDate() != null ? e.getNightDate() : e.getAsOf();
            if (when.getYear() == today.getYear()) {
                year += qty;
            }
            if (!when.isBefore(from24)) {
                last24 += qty;
            }
            if (!countsForTier(e, params)) {
                continue;
            }
            if (countFrom != null && when.isBefore(countFrom)) {
                continue;
            }
            tier += qty;
        }
        return new Counters(Math.max(tier, 0), Math.max(total, 0), Math.max(year, 0), Math.max(last24, 0));
    }

    static boolean countsForTier(LoyaltyLedgerEntry e, LoyaltyParams params) {
        if (e.getSource() == LoyaltyLedgerEntry.Source.OPENING_BALANCE) {
            return params.openingBalanceCountsForTier();
        }
        if (e.getMetric() == LoyaltyLedgerEntry.Metric.BONUS_NIGHT) {
            return params.bonusCountsForTier();
        }
        return true;
    }

    /** Highest tier whose threshold is reached; a member always holds at least the entry (lowest) tier. */
    public static Optional<LoyaltyTier> tierFor(List<LoyaltyTier> tiers, int nights) {
        List<LoyaltyTier> sorted = tiers.stream().sorted(Comparator.comparing(LoyaltyTier::getRank)).toList();
        if (sorted.isEmpty()) {
            return Optional.empty();
        }
        LoyaltyTier best = sorted.get(0);
        for (LoyaltyTier t : sorted) {
            if (nights >= t.getThreshold()) {
                best = t;
            }
        }
        return Optional.of(best);
    }

    public static Optional<LoyaltyTier> nextTier(List<LoyaltyTier> tiers, LoyaltyTier current) {
        return tiers.stream()
                .filter(t -> current == null || t.getRank() > current.getRank())
                .min(Comparator.comparing(LoyaltyTier::getRank));
    }

    public static Optional<LoyaltyTier> entryTier(List<LoyaltyTier> tiers) {
        return tiers.stream().min(Comparator.comparing(LoyaltyTier::getRank));
    }

    /** Status validity under the rule set's policy; {@code null} when the member has never stayed. */
    public static LocalDate validUntil(LoyaltyRuleSet.TierPolicy policy, LoyaltyParams params, LocalDate lastCheckout, LocalDate today) {
        if (policy == LoyaltyRuleSet.TierPolicy.ANNUAL_CYCLE) {
            // a status earned in year N holds through 31.12. of year N+1
            return LocalDate.of(today.getYear() + 1, 12, 31);
        }
        return lastCheckout == null ? null : lastCheckout.plusMonths(params.validityMonths());
    }

    /** Tier after an expiry: the entry tier (rolling), or {@code downgrade_step} levels lower (annual cycle). */
    public static Optional<LoyaltyTier> afterExpiry(LoyaltyRuleSet.TierPolicy policy, LoyaltyParams params,
                                                    List<LoyaltyTier> tiers, LoyaltyTier current, int nightsInNewWindow) {
        List<LoyaltyTier> sorted = tiers.stream().sorted(Comparator.comparing(LoyaltyTier::getRank)).toList();
        if (sorted.isEmpty()) {
            return Optional.empty();
        }
        LoyaltyTier earned = tierFor(sorted, nightsInNewWindow).orElse(sorted.get(0));
        if (policy != LoyaltyRuleSet.TierPolicy.ANNUAL_CYCLE || current == null) {
            return Optional.of(earned);
        }
        int idx = sorted.indexOf(current);
        int floorIdx = Math.max(0, (idx < 0 ? 0 : idx) - Math.max(params.downgradeStep(), 0));
        LoyaltyTier floor = sorted.get(floorIdx);
        return Optional.of(earned.getRank() >= floor.getRank() ? earned : floor);
    }
}
