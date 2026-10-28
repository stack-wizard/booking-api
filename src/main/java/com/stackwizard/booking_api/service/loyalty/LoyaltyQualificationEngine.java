package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyProgram;
import com.stackwizard.booking_api.model.LoyaltyQualificationStep;
import com.stackwizard.booking_api.model.LoyaltyReservationEvaluation;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.PmsReservation;
import com.stackwizard.booking_api.repository.LoyaltyChannelMapRepository;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyQualificationStepRepository;
import com.stackwizard.booking_api.repository.LoyaltyReservationEvaluationRepository;
import com.stackwizard.booking_api.repository.PmsReservationRepository;
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
 * Turns a PMS reservation into ledger nights. Idempotent: evaluating the same state twice changes nothing; a changed
 * state (dates, status, person) reverses what no longer holds and posts what newly qualifies. The reservation itself
 * belongs to the platform; this engine only decides what it means for the loyalty programme.
 */
@Service
public class LoyaltyQualificationEngine {
    public static final String NO_MATCH = "NO_MATCH";
    public static final String NOT_MEMBER = "NOT_MEMBER";

    private final Map<LoyaltyQualificationStep.RuleCode, QualificationRule> rules = new EnumMap<>(LoyaltyQualificationStep.RuleCode.class);
    private final PmsReservationRepository reservationRepo;
    private final LoyaltyReservationEvaluationRepository evaluationRepo;
    private final LoyaltyQualificationStepRepository stepRepo;
    private final LoyaltyChannelMapRepository channelMapRepo;
    private final LoyaltyLedgerRepository ledgerRepo;
    private final LoyaltyMemberRepository memberRepo;
    private final LoyaltyMatchingService matchingService;
    private final LoyaltyProgramService programService;
    private final LoyaltyLedgerService ledgerService;
    private final LoyaltyTierEngine tierEngine;
    private final LoyaltyEvents events;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoyaltyQualificationEngine(List<QualificationRule> ruleBeans,
                                      PmsReservationRepository reservationRepo,
                                      LoyaltyReservationEvaluationRepository evaluationRepo,
                                      LoyaltyQualificationStepRepository stepRepo,
                                      LoyaltyChannelMapRepository channelMapRepo,
                                      LoyaltyLedgerRepository ledgerRepo,
                                      LoyaltyMemberRepository memberRepo,
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
        this.matchingService = matchingService;
        this.programService = programService;
        this.ledgerService = ledgerService;
        this.tierEngine = tierEngine;
        this.events = events;
        this.clock = loyaltyClock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void evaluate(LoyaltyReservationEvaluation evaluation) {
        PmsReservation r = reservationRepo.findById(evaluation.getReservationId()).orElse(null);
        if (r == null) {
            finish(evaluation, LoyaltyReservationEvaluation.Result.SKIPPED, null, 0, null, details("reservation deleted"));
            return;
        }
        // (re)match the person: a later import may bring the profile id / card, or the guest may have registered since
        CrmContact contact = matchingService.matchContact(r).map(m -> {
            if (!Objects.equals(r.getContactId(), m.contact().getId()) || r.getMatchMethod() != m.method()) {
                r.setContactId(m.contact().getId());
                r.setMatchMethod(m.method());
                r.setUpdatedAt(OffsetDateTime.now(clock));
                reservationRepo.save(r);
            }
            return m.contact();
        }).orElse(null);

        LoyaltyProgram program = null;
        LoyaltyMember member = null;
        if (contact != null) {
            for (LoyaltyProgram p : programService.list(r.getTenantId())) {
                if (!Boolean.TRUE.equals(p.getActive())) {
                    continue;
                }
                LoyaltyMember m = matchingService.memberFor(p.getId(), contact.getId()).orElse(null);
                if (m != null) {
                    program = p;
                    member = memberRepo.lockById(m.getId()).orElseThrow();
                    break;
                }
            }
        }

        List<LoyaltyLedgerEntry> posted = ledgerRepo.findByReservationIdAndSourceAndActiveTrue(r.getId(), LoyaltyLedgerEntry.Source.STAY);
        // nights credited to a member who no longer matches go back first
        for (Long otherMemberId : posted.stream().map(LoyaltyLedgerEntry::getMemberId).distinct().toList()) {
            if (member == null || !Objects.equals(otherMemberId, member.getId())) {
                LoyaltyMember other = memberRepo.lockById(otherMemberId).orElseThrow();
                int qty = ledgerService.reverseReservation(other, r.getId(), "reservation re-matched to another person");
                tierEngine.recompute(other, LoyaltyTierEngine.Context.engine());
                reversedEvent(other, r, qty, "REMATCHED");
            }
        }
        if (member == null) {
            String code = contact == null ? NO_MATCH : NOT_MEMBER;
            finish(evaluation, LoyaltyReservationEvaluation.Result.REJECTED, null, 0, code,
                    details(contact == null ? "no person matches the holder" : "holder is not a member of any programme"));
            return;
        }
        evaluation.setMemberId(member.getId());
        posted = ledgerRepo.findByReservationIdAndSourceAndActiveTrue(r.getId(), LoyaltyLedgerEntry.Source.STAY);

        LoyaltyRuleSet ruleSet = programService.ruleSetOn(program.getId(), r.getArrival());
        Outcome outcome = runPipeline(r, contact, member, program, ruleSet);

        Set<LocalDate> target = outcome.rejectCode == null ? new TreeSet<>(outcome.nights) : Set.of();
        Set<LocalDate> current = new HashSet<>();
        posted.forEach(e -> current.add(e.getNightDate()));

        if (outcome.rejectCode != null && current.isEmpty()) {
            Long memberId = member.getId();
            boolean alreadyTold = evaluationRepo.findFirstByReservationIdAndResultNotOrderByIdDesc(r.getId(), LoyaltyReservationEvaluation.Result.PENDING)
                    .filter(prev -> prev.getResult() == LoyaltyReservationEvaluation.Result.REJECTED)
                    .filter(prev -> Objects.equals(prev.getRejectCode(), outcome.rejectCode) && Objects.equals(prev.getMemberId(), memberId))
                    .isPresent();
            finish(evaluation, LoyaltyReservationEvaluation.Result.REJECTED, ruleSet, 0, outcome.rejectCode, outcome.details);
            // only a finished stay is worth telling the member about (claim window), and only once per reason
            if (r.getStatus() == PmsReservation.Status.CHECKED_OUT && !alreadyTold) {
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
        LocalDate checkout = checkoutDate(r);
        if (added > 0 && (member.getLastStayCheckout() == null || checkout.isAfter(member.getLastStayCheckout()))) {
            member.setLastStayCheckout(checkout);
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

    private Outcome runPipeline(PmsReservation r, CrmContact contact, LoyaltyMember member, LoyaltyProgram program, LoyaltyRuleSet ruleSet) {
        LoyaltyParams params = LoyaltyParams.of(ruleSet);
        QualificationContext ctx = new QualificationContext(r, contact, member, program, ruleSet, params,
                channelMapRepo.findByProgramIdOrderByIdAsc(program.getId()), stayNights(r));
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

    /** Nights actually slept: arrival up to the real check-out (early departure shortens, units never multiply, FR-04.5). */
    static List<LocalDate> stayNights(PmsReservation r) {
        List<LocalDate> nights = new ArrayList<>();
        LocalDate end = checkoutDate(r);
        for (LocalDate d = r.getArrival(); d.isBefore(end); d = d.plusDays(1)) {
            nights.add(d);
        }
        return nights;
    }

    static LocalDate checkoutDate(PmsReservation r) {
        if (r.getActualCheckOut() != null) {
            LocalDate actual = r.getActualCheckOut().atZoneSameInstant(java.time.ZoneId.of("Europe/Zagreb")).toLocalDate();
            if (actual.isBefore(r.getDeparture()) && !actual.isBefore(r.getArrival())) {
                return actual;
            }
        }
        return r.getDeparture();
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

    private ObjectNode stayData(PmsReservation r) {
        ObjectNode data = events.data();
        data.put("reservationId", r.getId());
        data.put("propertyTenantId", r.getPropertyTenantId());
        data.put("confirmationNo", r.getConfirmationNo());
        data.put("pmsReservationId", r.getPmsReservationId());
        data.put("arrival", r.getArrival().toString());
        data.put("departure", r.getDeparture().toString());
        return data;
    }

    private void rejectedEvent(LoyaltyMember member, PmsReservation r, Outcome outcome, int claimWindowDays) {
        ObjectNode data = stayData(r);
        data.put("rejectCode", outcome.rejectCode);
        data.put("claimWindowDays", claimWindowDays);
        data.put("claimDeadline", r.getDeparture().plusDays(claimWindowDays).toString());
        events.publish(LoyaltyEvents.STAY_REJECTED, member, data);
    }

    private void reversedEvent(LoyaltyMember member, PmsReservation r, int nights, String reason) {
        if (nights <= 0) {
            return;
        }
        ObjectNode data = stayData(r);
        data.put("nights", nights);
        data.put("reason", reason);
        events.publish(LoyaltyEvents.STAY_REVERSED, member, data);
    }
}
