package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;
import com.stackwizard.booking_api.model.LoyaltyTierHistory;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The only way to change a member's balance. Every write goes through here so uniqueness (one STAY night per
 * date), audit fields and tier recomputation are applied the same way for engine postings, imports and manual
 * corrections (FR-07: "kroz isti engine, ne direktnim upisom u bazu").
 */
@Service
public class LoyaltyLedgerService {
    private final LoyaltyLedgerRepository ledgerRepo;
    private final LoyaltyMemberRepository memberRepo;
    private final LoyaltyTierRepository tierRepo;
    private final LoyaltyTierEngine tierEngine;
    private final LoyaltyProgramService programService;
    private final LoyaltyEvents events;
    private final Clock clock;

    public LoyaltyLedgerService(LoyaltyLedgerRepository ledgerRepo,
                                LoyaltyMemberRepository memberRepo,
                                LoyaltyTierRepository tierRepo,
                                LoyaltyTierEngine tierEngine,
                                LoyaltyProgramService programService,
                                LoyaltyEvents events,
                                Clock loyaltyClock) {
        this.ledgerRepo = ledgerRepo;
        this.memberRepo = memberRepo;
        this.tierRepo = tierRepo;
        this.tierEngine = tierEngine;
        this.programService = programService;
        this.events = events;
        this.clock = loyaltyClock;
    }

    public enum AdjustmentType { ADD_NIGHTS, REMOVE_NIGHTS, SET_TIER }

    public record Adjustment(AdjustmentType type, Integer qty, String tierCode, LocalDate tierValidUntil,
                             String reason, Long reservationId, String reservationRef, boolean exception) {
    }

    /** Raw append used by the engine and importers; the caller holds the member lock and recomputes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LoyaltyLedgerEntry append(LoyaltyLedgerEntry entry) {
        if (entry.getActive() == null) {
            entry.setActive(true);
        }
        if (entry.getIsException() == null) {
            entry.setIsException(false);
        }
        if (entry.getAsOf() == null) {
            entry.setAsOf(entry.getNightDate() != null ? entry.getNightDate() : LocalDate.now(clock));
        }
        return ledgerRepo.save(entry);
    }

    /** Counter-entry: the original goes inactive, a REVERSAL row records who/why. Never deletes (FR-07.3). */
    @Transactional(propagation = Propagation.MANDATORY)
    public LoyaltyLedgerEntry reverseEntry(LoyaltyLedgerEntry original, String reason, Long operatorUserId) {
        if (!Boolean.TRUE.equals(original.getActive())) {
            throw new IllegalStateException("Ledger entry " + original.getId() + " is already reversed");
        }
        if (original.getSource() == LoyaltyLedgerEntry.Source.REVERSAL) {
            throw new IllegalArgumentException("A reversal cannot be reversed; post a new correction instead");
        }
        original.setActive(false);
        ledgerRepo.saveAndFlush(original);
        return ledgerRepo.save(LoyaltyLedgerEntry.builder()
                .tenantId(original.getTenantId())
                .memberId(original.getMemberId())
                .metric(original.getMetric())
                .qty(-original.getQty())
                .nightDate(original.getNightDate())
                .asOf(LocalDate.now(clock))
                .source(LoyaltyLedgerEntry.Source.REVERSAL)
                .active(false)
                .reservationId(original.getReservationId())
                .propertyTenantId(original.getPropertyTenantId())
                .ruleSetId(original.getRuleSetId())
                .reversalOfId(original.getId())
                .operatorUserId(operatorUserId)
                .reason(reason)
                .isException(false)
                .build());
    }

    /** Reverses every active STAY night of a reservation (storno / no-show after posting, FR-03.4). */
    @Transactional(propagation = Propagation.MANDATORY)
    public int reverseReservation(LoyaltyMember lockedMember, Long reservationId, String reason) {
        List<LoyaltyLedgerEntry> nights = ledgerRepo.findByReservationIdAndSourceAndActiveTrue(reservationId, LoyaltyLedgerEntry.Source.STAY);
        int qty = 0;
        for (LoyaltyLedgerEntry e : nights) {
            if (e.getMemberId().equals(lockedMember.getId())) {
                reverseEntry(e, reason, null);
                qty += e.getQty();
            }
        }
        return qty;
    }

    @Transactional
    public List<LoyaltyLedgerEntry> adjust(Long tenantId, Long memberId, Adjustment adj, Long operatorUserId) {
        if (adj == null || adj.type() == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (!StringUtils.hasText(adj.reason())) {
            throw new IllegalArgumentException("reason is required for a manual correction");
        }
        if (operatorUserId == null) {
            throw new IllegalArgumentException("A manual correction needs an identified operator");
        }
        LoyaltyMember member = memberRepo.lockById(memberId)
                .filter(m -> m.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Member not found"));
        String reason = adj.reason().trim() + (StringUtils.hasText(adj.reservationRef()) ? " [ref " + adj.reservationRef().trim() + "]" : "");
        List<LoyaltyLedgerEntry> out = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        LoyaltyRuleSet ruleSet = programService.ruleSetOn(member.getProgramId(), today);
        switch (adj.type()) {
            case ADD_NIGHTS, REMOVE_NIGHTS -> {
                if (adj.qty() == null || adj.qty() <= 0) {
                    throw new IllegalArgumentException("qty must be a positive number of nights");
                }
                int signed = adj.type() == AdjustmentType.ADD_NIGHTS ? adj.qty() : -adj.qty();
                out.add(append(LoyaltyLedgerEntry.builder()
                        .tenantId(tenantId)
                        .memberId(memberId)
                        .metric(LoyaltyLedgerEntry.Metric.QUALIFYING_NIGHT)
                        .qty(signed)
                        .asOf(today)
                        .source(LoyaltyLedgerEntry.Source.ADJUSTMENT)
                        .reservationId(adj.reservationId())
                        .ruleSetId(ruleSet.getId())
                        .operatorUserId(operatorUserId)
                        .reason(reason)
                        .isException(adj.exception())
                        .build()));
                tierEngine.recompute(member, new LoyaltyTierEngine.Context(LoyaltyTierHistory.Reason.ADJUSTMENT, operatorUserId, reason, false));
            }
            case SET_TIER -> {
                if (!StringUtils.hasText(adj.tierCode())) {
                    throw new IllegalArgumentException("tierCode is required");
                }
                LoyaltyTier tier = tierRepo.findByRuleSetIdOrderByRankAsc(ruleSet.getId()).stream()
                        .filter(t -> t.getCode().equalsIgnoreCase(adj.tierCode().trim()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown tier " + adj.tierCode()));
                setTier(member, tier, adj.tierValidUntil(), LoyaltyTierHistory.Reason.ADJUSTMENT, operatorUserId, reason, false);
            }
        }
        ObjectNode data = events.data();
        data.put("adjustmentType", adj.type().name());
        data.put("qty", adj.qty() == null ? 0 : adj.qty());
        data.put("reason", reason);
        data.put("exception", adj.exception());
        events.publish(LoyaltyEvents.ADJUSTMENT_CREATED, member, data);
        return out;
    }

    @Transactional
    public LoyaltyLedgerEntry reverse(Long tenantId, Long entryId, String reason, Long operatorUserId) {
        if (!StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("reason is required");
        }
        LoyaltyLedgerEntry entry = ledgerRepo.findByIdAndTenantId(entryId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger entry not found"));
        LoyaltyMember member = memberRepo.lockById(entry.getMemberId()).orElseThrow();
        LoyaltyLedgerEntry reversal = reverseEntry(entry, reason.trim(), operatorUserId);
        tierEngine.recompute(member, new LoyaltyTierEngine.Context(LoyaltyTierHistory.Reason.ADJUSTMENT, operatorUserId, reason.trim(), false));
        return reversal;
    }

    /** Assigned tier by decision (migration, manual): held as an override until it expires or is out-earned. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setTier(LoyaltyMember member, LoyaltyTier tier, LocalDate validUntil, LoyaltyTierHistory.Reason reason,
                        Long operatorUserId, String comment, boolean suppressEvents) {
        LoyaltyTier previous = member.getAssignedTierId() == null ? null : tierRepo.findById(member.getAssignedTierId()).orElse(null);
        member.setAssignedTierId(tier.getId());
        member.setTierOverride(true);
        if (validUntil != null) {
            member.setTierValidUntil(validUntil);
        }
        memberRepo.save(member);
        tierEngine.recordHistory(member, previous, tier, reason, operatorUserId, comment);
        tierEngine.recompute(member, new LoyaltyTierEngine.Context(reason, operatorUserId, comment, suppressEvents));
        if (!suppressEvents && previous != null && !previous.getCode().equalsIgnoreCase(tier.getCode())) {
            ObjectNode data = events.data();
            data.put("fromTier", previous.getCode());
            data.put("toTier", tier.getCode());
            data.put("reason", reason.name());
            events.publish(tier.getRank() > previous.getRank() ? LoyaltyEvents.TIER_UPGRADED : LoyaltyEvents.TIER_DOWNGRADED, member, data);
        }
    }
}
