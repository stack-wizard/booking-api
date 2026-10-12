package com.stackwizard.booking_api.config;

import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.service.EventReservationSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Confirmed rent lines for the demo event, after the SQL seed has committed.
 * A function without a room is skipped. One function failing (no price, the hall is taken)
 * does not roll back the event or the other functions. The next startup retries a function
 * that still has no active line.
 */
@Component
class DhSalesFlowSeedReservations {
    private static final Logger log = LoggerFactory.getLogger(DhSalesFlowSeedReservations.class);

    private final ReservationRepository reservationRepo;
    private final EventReservationSync reservationSync;

    DhSalesFlowSeedReservations(ReservationRepository reservationRepo, EventReservationSync reservationSync) {
        this.reservationRepo = reservationRepo;
        this.reservationSync = reservationSync;
    }

    void holdMissing(Event event, List<EventFunction> functions) {
        for (EventFunction function : functions) {
            if (function.getResourceId() == null) {
                continue;
            }
            if (!reservationRepo.findActiveByEventFunctionId(function.getTenantId(), function.getId()).isEmpty()) {
                continue;
            }
            try {
                reservationSync.syncFunction(event, function);
                log.info("Demo sales-flow reservation created for function {} ({})", function.getId(), function.getName());
            } catch (RuntimeException ex) {
                log.warn(
                        "Demo sales-flow reservation for function {} ({}) was not created; the seed stays: {}",
                        function.getId(), function.getName(), ex.getMessage(), ex);
            }
        }
    }
}
