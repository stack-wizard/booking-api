package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;
import com.stackwizard.booking_api.model.LoyaltyTierHistory;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierHistoryRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Recomputes counters and tier from the ledger after every posting (FR-02.1). Upgrades are immediate; a lower
 * computed tier only replaces an engine-assigned tier (a reversal undoing the stay that upgraded). Imported or
 * manually set tiers ({@code tier_override}) hold until they expire; expiry itself is {@link LoyaltyExpiryJob}.
 */
@Service
public class LoyaltyTierEngine {
    private final LoyaltyMemberRepository memberRepo;
    private final LoyaltyLedgerRepository ledgerRepo;
    private final LoyaltyTierRepository tierRepo;
    private final LoyaltyTierHistoryRepository historyRepo;
    private final LoyaltyProgramService programService;
    private final LoyaltyEvents events;
    private final Clock clock;

    public LoyaltyTierEngine(LoyaltyMemberRepository memberRepo,
                             LoyaltyLedgerRepository ledgerRepo,
                             LoyaltyTierRepository tierRepo,
                             LoyaltyTierHistoryRepository historyRepo,
                             LoyaltyProgramService programService,
                             LoyaltyEvents events,
                             Clock loyaltyClock) {
        this.memberRepo = memberRepo;
        this.ledgerRepo = ledgerRepo;
        this.tierRepo = tierRepo;
        this.historyRepo = historyRepo;
        this.programService = programService;
        this.events = events;
        this.clock = loyaltyClock;
    }

    public record Context(LoyaltyTierHistory.Reason reason, Long operatorUserId, String comment, boolean suppressEvents) {
        public static Context engine() {
            return new Context(LoyaltyTierHistory.Reason.UPGRADE, null, null, false);
        }
    }

    public record Result(LoyaltyTier from, LoyaltyTier to, boolean changed) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result recompute(LoyaltyMember member, Context ctx) {
        LocalDate today = LocalDate.now(clock);
        LoyaltyRuleSet ruleSet = programService.ruleSetOn(member.getProgramId(), today);
        LoyaltyParams params = LoyaltyParams.of(ruleSet);
        List<LoyaltyTier> tiers = tierRepo.findByRuleSetIdOrderByRankAsc(ruleSet.getId());
        List<LoyaltyLedgerEntry> entries = ledgerRepo.findActiveBalanceEntries(member.getId());

        TierCalculator.Counters counters = TierCalculator.count(entries, params, member.getTierCountFrom(), today);
        member.setNightsTotal(counters.total());
        member.setNightsCurrentYear(counters.currentYear());
        member.setNights24m(counters.last24Months());
        member.setCountersYear(today.getYear());

        LoyaltyTier computed = TierCalculator.tierFor(tiers, counters.tierNights()).orElse(null);
        LoyaltyTier current = currentTier(member, tiers);
        member.setComputedTierId(computed == null ? null : computed.getId());

        LoyaltyTier target = current;
        if (computed != null) {
            if (current == null) {
                target = computed;
            } else if (computed.getRank() > current.getRank()) {
                target = computed;
            } else if (computed.getRank() < current.getRank() && !Boolean.TRUE.equals(member.getTierOverride())) {
                target = computed;
            }
        }
        LocalDate validUntil = TierCalculator.validUntil(ruleSet.getTierPolicy(), params, member.getLastStayCheckout(), today);
        if (validUntil != null && (member.getTierValidUntil() == null || validUntil.isAfter(member.getTierValidUntil()))) {
            member.setTierValidUntil(validUntil);
        }

        boolean changed = !sameTier(current, target);
        if (changed) {
            boolean up = current == null || target.getRank() > current.getRank();
            if (up && current != null && computed != null && target == computed) {
                // an earned upgrade clears an imported/manual override
                member.setTierOverride(false);
            }
            member.setAssignedTierId(target == null ? null : target.getId());
            LoyaltyTierHistory.Reason reason = ctx.reason() == LoyaltyTierHistory.Reason.UPGRADE && !up
                    ? LoyaltyTierHistory.Reason.RECOMPUTE : ctx.reason();
            historyRepo.save(LoyaltyTierHistory.builder()
                    .tenantId(member.getTenantId())
                    .memberId(member.getId())
                    .fromTierId(current == null ? null : current.getId())
                    .toTierId(target == null ? null : target.getId())
                    .reason(reason)
                    .ruleSetId(ruleSet.getId())
                    .validUntil(member.getTierValidUntil())
                    .operatorUserId(ctx.operatorUserId())
                    .comment(ctx.comment())
                    .build());
        } else if (current != null && !Objects.equals(current.getId(), member.getAssignedTierId())) {
            // same tier code under a newer rule set version
            member.setAssignedTierId(current.getId());
        }
        member.setUpdatedAt(OffsetDateTime.now(clock));
        memberRepo.save(member);

        if (!ctx.suppressEvents()) {
            if (changed && current != null && target != null) {
                ObjectNode data = events.data();
                data.put("fromTier", current.getCode());
                data.put("toTier", target.getCode());
                data.put("reason", ctx.reason().name());
                events.publish(target.getRank() > current.getRank() ? LoyaltyEvents.TIER_UPGRADED : LoyaltyEvents.TIER_DOWNGRADED, member, data);
            }
            publishNearNext(member, tiers, target, counters.tierNights(), params);
        }
        return new Result(current, target, changed);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordHistory(LoyaltyMember member, LoyaltyTier from, LoyaltyTier to, LoyaltyTierHistory.Reason reason,
                              Long operatorUserId, String comment) {
        historyRepo.save(LoyaltyTierHistory.builder()
                .tenantId(member.getTenantId())
                .memberId(member.getId())
                .fromTierId(from == null ? null : from.getId())
                .toTierId(to == null ? null : to.getId())
                .reason(reason)
                .validUntil(member.getTierValidUntil())
                .operatorUserId(operatorUserId)
                .comment(comment)
                .build());
    }

    /** Tiers are referenced by code across rule set versions; a stale id is re-resolved by code. */
    LoyaltyTier currentTier(LoyaltyMember member, List<LoyaltyTier> tiers) {
        if (member.getAssignedTierId() == null) {
            return null;
        }
        for (LoyaltyTier t : tiers) {
            if (t.getId().equals(member.getAssignedTierId())) {
                return t;
            }
        }
        Optional<LoyaltyTier> old = tierRepo.findById(member.getAssignedTierId());
        return old.flatMap(o -> tiers.stream().filter(t -> t.getCode().equalsIgnoreCase(o.getCode())).findFirst()).orElse(null);
    }

    private void publishNearNext(LoyaltyMember member, List<LoyaltyTier> tiers, LoyaltyTier current, int tierNights, LoyaltyParams params) {
        LoyaltyTier next = TierCalculator.nextTier(tiers, current).orElse(null);
        if (next == null) {
            return;
        }
        int missing = next.getThreshold() - tierNights;
        Integer lastNotified = member.getAttrs() != null && member.getAttrs().hasNonNull("nearNextNotifiedFor")
                ? member.getAttrs().get("nearNextNotifiedFor").asInt() : null;
        if (missing > 0 && missing <= params.nearNextThreshold() && !Objects.equals(lastNotified, next.getRank())) {
            ObjectNode data = events.data();
            data.put("nextTier", next.getCode());
            data.put("nightsMissing", missing);
            events.publish(LoyaltyEvents.TIER_NEAR_NEXT, member, data);
            ((ObjectNode) member.getAttrs()).put("nearNextNotifiedFor", next.getRank());
            memberRepo.save(member);
        }
    }

    private static boolean sameTier(LoyaltyTier a, LoyaltyTier b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getCode().equalsIgnoreCase(b.getCode());
    }
}
