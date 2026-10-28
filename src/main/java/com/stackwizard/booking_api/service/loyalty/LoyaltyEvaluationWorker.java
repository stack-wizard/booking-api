package com.stackwizard.booking_api.service.loyalty;

import com.stackwizard.booking_api.model.LoyaltyReservationEvaluation;
import com.stackwizard.booking_api.repository.LoyaltyReservationEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Drains PENDING evaluations on every instance ({@code skip locked} + lease). Each evaluation commits on its own,
 * so one bad reservation never blocks the queue; after 5 attempts it stays PENDING for an operator to look at.
 */
@Service
public class LoyaltyEvaluationWorker {
    private static final Logger log = LoggerFactory.getLogger(LoyaltyEvaluationWorker.class);

    private final LoyaltyReservationEvaluationRepository evaluationRepo;
    private final LoyaltyQualificationEngine engine;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public LoyaltyEvaluationWorker(LoyaltyReservationEvaluationRepository evaluationRepo,
                                   LoyaltyQualificationEngine engine,
                                   TransactionTemplate tx,
                                   @Value("${loyalty.evaluation.enabled:true}") boolean enabled) {
        this.evaluationRepo = evaluationRepo;
        this.engine = engine;
        this.tx = tx;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${loyalty.evaluation.poll-ms:5000}", initialDelayString = "${loyalty.evaluation.initial-delay-ms:30000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        int processed;
        do {
            processed = drainOnce(200);
        } while (processed > 0);
    }

    public int drainOnce(int limit) {
        List<Long> claimed = tx.execute(status -> evaluationRepo.claimPending(limit));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        // UPDATE ... RETURNING has no order; FIFO keeps "first reservation wins the night" deterministic
        List<Long> ids = claimed.stream().sorted().toList();
        for (Long id : ids) {
            try {
                tx.executeWithoutResult(status -> {
                    LoyaltyReservationEvaluation ev = evaluationRepo.findById(id).orElse(null);
                    if (ev == null || ev.getResult() != LoyaltyReservationEvaluation.Result.PENDING) {
                        return;
                    }
                    engine.evaluate(ev);
                    evaluationRepo.save(ev);
                });
            } catch (Exception ex) {
                log.warn("Loyalty evaluation {} failed", id, ex);
            }
        }
        return ids.size();
    }
}
