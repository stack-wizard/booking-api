package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;
import com.stackwizard.booking_api.model.LoyaltyTierHistory;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Daily status maintenance (FR-02.3, FR-11): expired statuses drop, calendar-year counters roll over, and members
 * whose status expires in 90 / 30 days get a {@code loyalty.tier.expiring} event. One instance runs it (advisory lock);
 * each member commits on its own so a crash resumes where it stopped.
 */
@Service
public class LoyaltyExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(LoyaltyExpiryJob.class);
    private static final long LOCK_KEY = 872_014_551_7L;
    static final List<Integer> EXPIRING_NOTICE_DAYS = List.of(90, 30);

    private final LoyaltyMemberRepository memberRepo;
    private final LoyaltyTierRepository tierRepo;
    private final LoyaltyLedgerRepository ledgerRepo;
    private final LoyaltyProgramService programService;
    private final LoyaltyTierEngine tierEngine;
    private final LoyaltyEvents events;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;

    public LoyaltyExpiryJob(LoyaltyMemberRepository memberRepo,
                            LoyaltyTierRepository tierRepo,
                            LoyaltyLedgerRepository ledgerRepo,
                            LoyaltyProgramService programService,
                            LoyaltyTierEngine tierEngine,
                            LoyaltyEvents events,
                            TransactionTemplate tx,
                            JdbcTemplate jdbc,
                            Clock loyaltyClock,
                            @Value("${loyalty.expiry.enabled:true}") boolean enabled) {
        this.memberRepo = memberRepo;
        this.tierRepo = tierRepo;
        this.ledgerRepo = ledgerRepo;
        this.programService = programService;
        this.tierEngine = tierEngine;
        this.events = events;
        this.tx = tx;
        this.jdbc = jdbc;
        this.clock = loyaltyClock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${loyalty.expiry.cron:0 15 2 * * *}", zone = "Europe/Zagreb")
    public void runDaily() {
        if (!enabled) {
            return;
        }
        // session-level lock held for the whole run; released explicitly (or when the connection dies)
        Boolean locked = jdbc.queryForObject("select pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            run(LocalDate.now(clock));
        } finally {
            jdbc.queryForObject("select pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
        }
    }

    public void run(LocalDate today) {
        int expired = 0;
        List<Long> ids;
        int progress;
        do {
            ids = memberRepo.findIdsWithExpiredTier(today, PageRequest.of(0, 500));
            progress = 0;
            for (Long id : ids) {
                try {
                    if (Boolean.TRUE.equals(tx.execute(s -> expire(id, today)))) {
                        expired++;
                        progress++;
                    }
                } catch (Exception ex) {
                    log.warn("Loyalty expiry failed for member {}", id, ex);
                }
            }
        } while (ids.size() == 500 && progress > 0);
        int rolled = 0;
        do {
            ids = memberRepo.findIdsWithStaleCounters(today.getYear(), PageRequest.of(0, 500));
            for (Long id : ids) {
                tx.executeWithoutResult(s -> memberRepo.lockById(id).ifPresent(m ->
                        tierEngine.recompute(m, new LoyaltyTierEngine.Context(LoyaltyTierHistory.Reason.RECOMPUTE, null, "year rollover", false))));
                rolled++;
            }
        } while (ids.size() == 500);
        int notified = notifyExpiring(today);
        log.info("Loyalty daily: {} statuses expired, {} counters rolled, {} expiry notices", expired, rolled, notified);
    }

    /** Drops an expired status; nights before today stop counting toward the tier (a fresh window starts). */
    boolean expire(Long memberId, LocalDate today) {
        LoyaltyMember m = memberRepo.lockById(memberId).orElse(null);
        if (m == null || m.getTierValidUntil() == null || !m.getTierValidUntil().isBefore(today)) {
            return false;
        }
        LoyaltyRuleSet rs = programService.ruleSetOn(m.getProgramId(), today);
        LoyaltyParams params = LoyaltyParams.of(rs);
        List<LoyaltyTier> tiers = tierRepo.findByRuleSetIdOrderByRankAsc(rs.getId());
        LoyaltyTier current = tierEngine.currentTier(m, tiers);
        int windowNights = TierCalculator.count(ledgerRepo.findActiveBalanceEntries(m.getId()), params, today, today).tierNights();
        LoyaltyTier next = TierCalculator.afterExpiry(rs.getTierPolicy(), params, tiers, current, windowNights).orElse(null);
        LocalDate expiredOn = m.getTierValidUntil();
        m.setTierCountFrom(today);
        m.setTierOverride(false);
        m.setTierValidUntil(rs.getTierPolicy() == LoyaltyRuleSet.TierPolicy.ANNUAL_CYCLE ? LocalDate.of(today.getYear(), 12, 31) : null);
        m.setAssignedTierId(next == null ? null : next.getId());
        m.setUpdatedAt(OffsetDateTime.now(clock));
        memberRepo.save(m);
        tierEngine.recordHistory(m, current, next, LoyaltyTierHistory.Reason.EXPIRY, null, "status expired " + expiredOn);
        tierEngine.recompute(m, new LoyaltyTierEngine.Context(LoyaltyTierHistory.Reason.EXPIRY, null, null, true));
        if (current != null && next != null && !current.getCode().equalsIgnoreCase(next.getCode())) {
            ObjectNode data = events.data();
            data.put("fromTier", current.getCode());
            data.put("toTier", next.getCode());
            data.put("reason", "EXPIRY");
            data.put("expiredOn", expiredOn.toString());
            events.publish(LoyaltyEvents.TIER_DOWNGRADED, m, data);
        }
        return true;
    }

    int notifyExpiring(LocalDate today) {
        int count = 0;
        for (Integer days : EXPIRING_NOTICE_DAYS) {
            LocalDate on = today.plusDays(days);
            for (LoyaltyMember m : memberRepo.findExpiringOn(List.of(on))) {
                Integer sent = tx.execute(s -> {
                    ObjectNode data = events.data();
                    data.put("daysLeft", days);
                    data.put("expiresOn", on.toString());
                    events.publish(LoyaltyEvents.TIER_EXPIRING, m, data);
                    return 1;
                });
                count += sent == null ? 0 : sent;
            }
        }
        return count;
    }
}
