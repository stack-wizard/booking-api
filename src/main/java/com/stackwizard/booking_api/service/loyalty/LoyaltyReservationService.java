package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyReservation;
import com.stackwizard.booking_api.model.LoyaltyReservationEvaluation;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyReservationEvaluationRepository;
import com.stackwizard.booking_api.repository.LoyaltyReservationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Reservation snapshots: listing, the evaluation queue, manual matching (match queue, FR-04.3). */
@Service
public class LoyaltyReservationService {
    private final LoyaltyReservationRepository reservationRepo;
    private final LoyaltyReservationEvaluationRepository evaluationRepo;
    private final LoyaltyMemberRepository memberRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoyaltyReservationService(LoyaltyReservationRepository reservationRepo,
                                     LoyaltyReservationEvaluationRepository evaluationRepo,
                                     LoyaltyMemberRepository memberRepo) {
        this.reservationRepo = reservationRepo;
        this.evaluationRepo = evaluationRepo;
        this.memberRepo = memberRepo;
    }

    public record ReservationView(LoyaltyReservation reservation, List<LoyaltyReservationEvaluation> evaluations) {
    }

    public Page<LoyaltyReservation> search(Long tenantId, LoyaltyReservation.Status status, Long propertyTenantId, boolean unmatched, Pageable pageable) {
        return reservationRepo.search(tenantId, status, propertyTenantId, unmatched, pageable);
    }

    public ReservationView view(Long tenantId, Long reservationId) {
        LoyaltyReservation r = require(tenantId, reservationId);
        return new ReservationView(r, evaluationRepo.findByReservationIdOrderByIdAsc(r.getId()));
    }

    public List<LoyaltyReservation> forMember(Long tenantId, Long memberId) {
        memberRepo.findByIdAndTenantId(memberId, tenantId).orElseThrow(() -> new IllegalArgumentException("Member not found"));
        return reservationRepo.findByMemberIdOrderByArrivalDesc(memberId);
    }

    /** Queues a (re)evaluation; the worker picks it up on any instance. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(LoyaltyReservation r) {
        if (evaluationRepo.existsByReservationIdAndResult(r.getId(), LoyaltyReservationEvaluation.Result.PENDING)) {
            return;
        }
        evaluationRepo.save(LoyaltyReservationEvaluation.builder()
                .tenantId(r.getTenantId())
                .reservationId(r.getId())
                .result(LoyaltyReservationEvaluation.Result.PENDING)
                .nightsPosted(0)
                .attempts(0)
                .details(objectMapper.createObjectNode())
                .build());
    }

    @Transactional
    public ReservationView matchManually(Long tenantId, Long reservationId, Long memberId) {
        LoyaltyReservation r = require(tenantId, reservationId);
        LoyaltyMember member = memberRepo.findByIdAndTenantId(memberId, tenantId)
                .filter(m -> m.getProgramId().equals(r.getProgramId()))
                .orElseThrow(() -> new IllegalArgumentException("Member not found"));
        r.setMemberId(member.getId());
        r.setMatchMethod(LoyaltyReservation.MatchMethod.MANUAL);
        r.setUpdatedAt(OffsetDateTime.now());
        reservationRepo.save(r);
        enqueue(r);
        return view(tenantId, reservationId);
    }

    @Transactional
    public ReservationView reevaluate(Long tenantId, Long reservationId) {
        LoyaltyReservation r = require(tenantId, reservationId);
        enqueue(r);
        return view(tenantId, reservationId);
    }

    private LoyaltyReservation require(Long tenantId, Long id) {
        return reservationRepo.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
    }
}
