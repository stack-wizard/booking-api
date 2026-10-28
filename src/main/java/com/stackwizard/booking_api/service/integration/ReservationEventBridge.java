package com.stackwizard.booking_api.service.integration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.ReservationRequestAmendedEvent;
import com.stackwizard.booking_api.service.ReservationRequestCancelledEvent;
import com.stackwizard.booking_api.service.ReservationRequestFinalizedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mirrors the in-process reservation events into the outbox inside the same transaction (BEFORE_COMMIT), so an
 * external e-mail service sees exactly the reservations that committed. Published on the ORG tenant: one
 * subscription per chain, the hotel is in the payload.
 */
@Component
public class ReservationEventBridge {
    private final IntegrationEventPublisher publisher;
    private final ReservationRequestRepository requestRepo;
    private final PlatformTenantMappingRepository mappingRepo;

    public ReservationEventBridge(IntegrationEventPublisher publisher,
                                  ReservationRequestRepository requestRepo,
                                  PlatformTenantMappingRepository mappingRepo) {
        this.publisher = publisher;
        this.requestRepo = requestRepo;
        this.mappingRepo = mappingRepo;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onFinalized(ReservationRequestFinalizedEvent event) {
        publish("reservation.confirmed", event.reservationRequestId(), null);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAmended(ReservationRequestAmendedEvent event) {
        publish("reservation.amended", event.reservationRequestId(), null);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCancelled(ReservationRequestCancelledEvent event) {
        publish("reservation.cancelled", event.reservationRequestId(), event.cancellationRequestId());
    }

    private void publish(String type, Long requestId, Long cancellationRequestId) {
        ReservationRequest request = requestRepo.findById(requestId).orElse(null);
        if (request == null) {
            return;
        }
        PlatformTenantMapping mapping = mappingRepo.findByTenantId(request.getTenantId()).orElse(null);
        Long orgTenantId = mapping != null && mapping.getParentTenantId() != null ? mapping.getParentTenantId() : request.getTenantId();
        ObjectNode data = publisher.newPayload();
        data.put("reservationRequestId", request.getId());
        data.put("propertyTenantId", request.getTenantId());
        if (mapping != null) {
            data.put("hotelCode", mapping.getHotelCode());
            data.put("hotelName", mapping.getName());
        }
        data.put("status", request.getStatus() == null ? null : request.getStatus().name());
        data.put("channel", request.getType() == null ? null : request.getType().name());
        data.put("confirmationCode", request.getConfirmationCode());
        data.put("confirmedAt", request.getConfirmedAt() == null ? null : request.getConfirmedAt().toString());
        if (cancellationRequestId != null) {
            data.put("cancellationRequestId", cancellationRequestId);
        }
        ObjectNode guest = data.putObject("guest");
        guest.put("name", request.getCustomerName());
        guest.put("email", request.getCustomerEmail());
        guest.put("phone", request.getCustomerPhone());
        guest.put("country", request.getCustomerCountry());
        publisher.publish(orgTenantId, type, "reservation_request", request.getId(), data);
    }
}
