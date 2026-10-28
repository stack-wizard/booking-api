package com.stackwizard.booking_api.service.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.EventDelivery;
import com.stackwizard.booking_api.model.EventSubscription;
import com.stackwizard.booking_api.model.IntegrationEvent;
import com.stackwizard.booking_api.repository.EventDeliveryRepository;
import com.stackwizard.booking_api.repository.EventSubscriptionRepository;
import com.stackwizard.booking_api.repository.IntegrationEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Transactional outbox: the event row is written in the caller's transaction, so it exists exactly when the
 * business change commits. Delivery happens later in {@link IntegrationEventDispatcher}.
 */
@Service
public class IntegrationEventPublisher {
    private final IntegrationEventRepository eventRepo;
    private final EventSubscriptionRepository subscriptionRepo;
    private final EventDeliveryRepository deliveryRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntegrationEventPublisher(IntegrationEventRepository eventRepo,
                                     EventSubscriptionRepository subscriptionRepo,
                                     EventDeliveryRepository deliveryRepo) {
        this.eventRepo = eventRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.deliveryRepo = deliveryRepo;
    }

    public ObjectNode newPayload() {
        return objectMapper.createObjectNode();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IntegrationEvent publish(Long tenantId, String type, String aggregateType, Object aggregateId, ObjectNode data) {
        UUID eventUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventUuid.toString());
        envelope.put("type", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", now.toString());
        envelope.put("tenantId", tenantId);
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", String.valueOf(aggregateId));
        envelope.set("data", data == null ? objectMapper.createObjectNode() : data);
        IntegrationEvent event = eventRepo.save(IntegrationEvent.builder()
                .tenantId(tenantId)
                .eventUuid(eventUuid)
                .type(type)
                .version(1)
                .aggregateType(aggregateType)
                .aggregateId(String.valueOf(aggregateId))
                .occurredAt(now)
                .payload(envelope)
                .build());
        for (EventSubscription sub : subscriptionRepo.findByTenantIdAndActiveTrue(tenantId)) {
            if (EventTypeMatcher.matches(sub.getEventTypes(), type)) {
                deliveryRepo.save(EventDelivery.builder()
                        .tenantId(tenantId)
                        .eventId(event.getId())
                        .subscriptionId(sub.getId())
                        .status(EventDelivery.Status.PENDING)
                        .attempts(0)
                        .nextAttemptAt(now)
                        .build());
            }
        }
        return event;
    }
}
