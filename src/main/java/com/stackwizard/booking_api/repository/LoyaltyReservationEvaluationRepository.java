package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyReservationEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LoyaltyReservationEvaluationRepository extends JpaRepository<LoyaltyReservationEvaluation, Long> {
    List<LoyaltyReservationEvaluation> findByReservationIdOrderByIdAsc(Long reservationId);

    Optional<LoyaltyReservationEvaluation> findFirstByReservationIdAndResultNotOrderByIdDesc(
            Long reservationId, LoyaltyReservationEvaluation.Result result);

    boolean existsByReservationIdAndResult(Long reservationId, LoyaltyReservationEvaluation.Result result);

    /** Claims due PENDING evaluations across instances: skip locked + short lease. */
    @Query(value = """
            update loyalty_reservation_evaluation set lease_until = now() + interval '5 minutes', attempts = attempts + 1
            where id in (select id from loyalty_reservation_evaluation
                         where result = 'PENDING' and (lease_until is null or lease_until < now()) and attempts < 5
                         order by id limit :limit for update skip locked)
            returning id
            """, nativeQuery = true)
    List<Long> claimPending(int limit);
}
