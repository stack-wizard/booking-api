package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyQualificationStep;
import com.stackwizard.booking_api.model.LoyaltyReservation;
import com.stackwizard.booking_api.model.LoyaltyReservationEvaluation;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.LoyaltyChannelMapRepository;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyQualificationStepRepository;
import com.stackwizard.booking_api.repository.LoyaltyReservationEvaluationRepository;
import com.stackwizard.booking_api.repository.LoyaltyReservationRepository;
import com.stackwizard.booking_api.service.loyalty.qualification.QualificationContext;
import com.stackwizard.booking_api.service.loyalty.qualification.QualificationRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a reservation snapshot into ledger nights. Idempotent: evaluating the same state twice changes nothing;
 * a changed state (dates, status, member) reverses what no longer holds and posts what newly qualifies.
 */
@Service
public class LoyaltyQualificationEngine {
    public static final String NO_MATCH = "NO_MATCH";

    private final Map<LoyaltyQualificationStep.RuleCode, QualificationRule> rules = new EnumMap<>(LoyaltyQualificationStep.RuleCode.class);
    private final LoyaltyReservationRepository reservationRepo;
    private final LoyaltyReservationEvaluationRepository evaluationRepo;
    private final LoyaltyQualificationStepRepository stepRepo;
    private final LoyaltyChannelMapRepository channelMapRepo;
    private final LoyaltyLedgerRepository ledgerRepo;
    private final LoyaltyMemberRepository memberRepo;
    private final CrmContactRepository contactRepo;
    private final LoyaltyMatchingService matchingService;
    private final LoyaltyProgramService programService;
    private final LoyaltyLedgerService ledgerService;
    private final LoyaltyTierEngine tierEngine;
    private final LoyaltyEvents events;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoyaltyQualificationEngine(List<QualificationRule> ruleBeans,
                                      LoyaltyReservationRepository reservationRepo,
                                      LoyaltyReservationEvaluationRepository evaluationRepo,
                                      LoyaltyQualificationStepRepository stepRepo,
                                      LoyaltyChannelMapRepository channelMapRepo,
                                      LoyaltyLedgerRepository ledgerRepo,
                                      LoyaltyMemberRepository memberRepo,
                                      CrmContactRepository contactRepo,
                                      LoyaltyMatchingService matchingService,
                                      LoyaltyProgramService programService,
                                      LoyaltyLedgerService ledgerService,
                                      LoyaltyTierEngine tierEngine,
                                      LoyaltyEvents events,
                                      Clock loyaltyClock) {
        for (QualificationRule r : ruleBeans) {
            rules.put(r.code(), r);
        }
        this.reservationRepo = reservationRepo;
        this.evaluationRepo = evaluationRepo;
        this.stepRepo = stepRepo;
        this.channelMapRepo = channelMapRepo;
        this.ledgerRepo = ledgerRepo;
        this.memberRepo = memberRepo;
        this.contactRepo = contactRepo;
        this.matchingService = matchingService;
        this.programService = programService;
        this.ledgerService = ledgerService;
        this.tierEngine = tierEngine;
        this.events = events;
        this.clock = loyaltyClock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluate(LoyaltyReservationEvaluation evaluation) {
        LoyaltyReservation r = reservationRepo.findById(evaluation.getReservationId()).orElse(null);
        if (r == null) {
            finish(evaluation, LoyaltyReservationEvaluation.Result.SKIPPED, null, 0, null, details("reservation deleted"));
            return;
        }
        // (re)match: a later import may bring the card number or a member may have registered since
        LoyaltyMatchingService.Match match = matchingService.match(r).orElse(null);
        r.setMemberId(match == null ? null : match.member().getId());
        r.setMatchMethod(match == null ? null : match.method());
        r.setUpdatedAt(OffsetDateTime.now(clock));
        reservationRepo.save(r);

        List<LoyaltyLedgerEntry> posted = ledgerRepo.findByReservationIdAndSourceAndActiveTrue(r.getId(), LoyaltyLedgerEntry.Source.STAY);
        // nights credited to a member who no longer matches go back first
        for (Long otherMemberId : posted.stream().map(LoyaltyLedgerEntry::getMemberId).distinct().toList()) {
            if (!Objects.equals(otherMemberId, r.getMemberId())) {
                LoyaltyMember other = memberRepo.lockById(otherMemberId).orElseThrow();
                int qty = ledgerService.reverseReservation(other, r.getId(), "reservation re-matched to another member");
                tierEngine.recompute(other, LoyaltyTierEngine.Context.engine());
                reversedEvent(other, r, qty, "REMATCHED");
            }
        }
        if (match == null) {
            ObjectNode d = details("no member matches holder");
            finish(evaluation, LoyaltyReservationEvaluation.Result.REJECTED, null, 0, NO_MATCH, d);
            return;
        }
        LoyaltyMember member = memberRepo.lockById(match.member().getId()).orElseThrow();
        evaluation.setMemberId(member.getId());
        posted = ledgerRepo.findByReservationIdAndSourceAndActiveTrue(r.getId(), LoyaltyLedgerEntry.Source.STAY);

        LoyaltyRuleSet ruleSet = programService.ruleSetOn(r.getProgramId(), r.getArrival());
        Outcome outcome = runPipeline(r, member, ruleSet);

        Set<LocalDate> target = outcome.rejectCode == null ? new TreeSet<>(outcome.nights) : Set.of();
        Set<LocalDate> current = new HashSet<>();
        posted.forEach(e -> current.add(e.getNightDate()));

        if (outcome.rejectCode != null && current.isEmpty()) {
            boolean alreadyTold = evaluationRepo.findFirstByReservationIdAndResultNotOrderByIdDesc(r.getId(), LoyaltyReservationEvaluation.Result.PENDING)
                    .filter(prev -> prev.getResult() == LoyaltyReservationEvaluation.Result.REJECTED)
                    .filter(prev -> Objects.equals(prev.getRejectCode(), outcome.rejectCode) && Objects.equals(prev.getMemberId(), member.getId()))
                    .isPresent();
            finish(evaluation, LoyaltyReservationEvaluation.Result.REJECTED, ruleSet, 0, outcome.rejectCode, outcome.details);
            // only a finished stay is worth telling the member about (claim window), and only once per reason
            if (r.getStatus() == LoyaltyReservation.Status.CHECKED_OUT && !alreadyTold) {
                rejectedEvent(member, r, outcome, LoyaltyParams.of(ruleSet).claimWindowDays());
            }
            return;
        }
        if (current.equals(target)) {
            finish(evaluation, LoyaltyReservationEvaluation.Result.SKIPPED, ruleSet, 0, null, outcome.details.put("note", "unchanged"));
            return;
        }
        int reversed = 0;
        for (LoyaltyLedgerEntry e : posted) {
            if (!target.contains(e.getNightDate())) {
                ledgerService.reverseEntry(e, "reservation " + r.getStatus().name().toLowerCase() + " or changed", null);
                reversed++;
            }
        }
        int added = 0;
        for (LocalDate night : target) {
            if (current.contains(night)) {
                continue;
            }
            ledgerService.append(LoyaltyLedgerEntry.builder()
                    .tenantId(r.getTenantId())
                    .memberId(member.getId())
                    .metric(LoyaltyLedgerEntry.Metric.QUALIFYING_NIGHT)
                    .qty(1)
                    .nightDate(night)
                    .asOf(night)
                    .source(LoyaltyLedgerEntry.Source.STAY)
                    .reservationId(r.getId())
                    .propertyTenantId(r.getPropertyTenantId())
                    .ruleSetId(ruleSet.getId())
                    .build());
            added++;
        }
        if (added > 0 && (member.getLastStayCheckout() == null || r.getDeparture().isAfter(member.getLastStayCheckout()))) {
            member.setLastStayCheckout(r.getDeparture());
        }
        tierEngine.recompute(member, LoyaltyTierEngine.Context.engine());

        if (added > 0) {
            outcome.details.put("nightsAdded", added).put("nightsReversed", reversed);
            finish(evaluation, LoyaltyReservationEvaluation.Result.POSTED, ruleSet, added, null, outcome.details);
            ObjectNode data = stayData(r);
            data.put("nights", added);
            events.publish(LoyaltyEvents.STAY_POSTED, member, data);
        } else {
            outcome.details.put("nightsReversed", reversed);
            finish(evaluation, LoyaltyReservationEvaluation.Result.REVERSED, ruleSet, 0, outcome.rejectCode, outcome.details);
            reversedEvent(member, r, reversed, outcome.rejectCode);
        }
    }

    private record Outcome(String rejectCode, List<LocalDate> nights, ObjectNode details) {
    }

    private Outcome runPipeline(LoyaltyReservation r, LoyaltyMember member, LoyaltyRuleSet ruleSet) {
        LoyaltyParams params = LoyaltyParams.of(ruleSet);
        String lastName = contactRepo.findById(member.getContactId()).map(CrmContact::getLastName).orElse(null);
        QualificationContext ctx = new QualificationContext(r, member, lastName, ruleSet, params,
                channelMapRepo.findByProgramIdOrderByIdAsc(r.getProgramId()), stayNights(r));
        ObjectNode details = objectMapper.createObjectNode();
        details.put("ruleSetVersion", ruleSet.getVersion());
        ArrayNode steps = details.putArray("steps");
        for (LoyaltyQualificationStep step : stepRepo.findByRuleSetIdOrderByStepOrderAsc(ruleSet.getId())) {
            if (!Boolean.TRUE.equals(step.getEnabled())) {
                continue;
            }
            QualificationRule rule = rules.get(step.getRuleCode());
            if (rule == null) {
                throw new IllegalStateException("No implementation for qualification rule " + step.getRuleCode());
            }
            QualificationRule.Outcome o = rule.check(ctx, step.getParams());
            ObjectNode s = steps.addObject();
            s.put("rule", step.getRuleCode().name());
            s.put("passed", o.passed());
            if (o.detail() != null) {
                s.put("detail", o.detail());
            }
            if (!o.passed()) {
                return new Outcome(step.getRejectCode(), List.of(), details);
            }
        }
        if (ctx.nights().isEmpty()) {
            return new Outcome("NO_NIGHTS", List.of(), details);
        }
        return new Outcome(null, new ArrayList<>(ctx.nights()), details);
    }

    /** One night per date of the stay; units on the same reservation never add nights (FR-04.5). */
    static List<LocalDate> stayNights(LoyaltyReservation r) {
        List<LocalDate> nights = new ArrayList<>();
        for (LocalDate d = r.getArrival(); d.isBefore(r.getDeparture()); d = d.plusDays(1)) {
            nights.add(d);
        }
        return nights;
    }

    private void finish(LoyaltyReservationEvaluation ev, LoyaltyReservationEvaluation.Result result, LoyaltyRuleSet ruleSet,
                        int nights, String rejectCode, ObjectNode details) {
        ev.setResult(result);
        ev.setRuleSetId(ruleSet == null ? null : ruleSet.getId());
        ev.setNightsPosted(nights);
        ev.setRejectCode(rejectCode);
        ev.setDetails(details);
        ev.setLeaseUntil(null);
        ev.setProcessedAt(OffsetDateTime.now(clock));
    }

    private ObjectNode details(String note) {
        return objectMapper.createObjectNode().put("note", note);
    }

    private ObjectNode stayData(LoyaltyReservation r) {
        ObjectNode data = events.data();
        data.put("reservationId", r.getId());
        data.put("propertyTenantId", r.getPropertyTenantId());
        data.put("confirmationNo", r.getConfirmationNo());
        data.put("arrival", r.getArrival().toString());
        data.put("departure", r.getDeparture().toString());
        return data;
    }

    private void rejectedEvent(LoyaltyMember member, LoyaltyReservation r, Outcome outcome, int claimWindowDays) {
        ObjectNode data = stayData(r);
        data.put("rejectCode", outcome.rejectCode);
        data.put("claimWindowDays", claimWindowDays);
        data.put("claimDeadline", r.getDeparture().plusDays(claimWindowDays).toString());
        events.publish(LoyaltyEvents.STAY_REJECTED, member, data);
    }

    private void reversedEvent(LoyaltyMember member, LoyaltyReservation r, int nights, String reason) {
        if (nights <= 0) {
            return;
        }
        ObjectNode data = stayData(r);
        data.put("nights", nights);
        data.put("reason", reason);
        events.publish(LoyaltyEvents.STAY_REVERSED, member, data);
    }
}
