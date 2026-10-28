package com.stackwizard.booking_api.service.integration;

import com.stackwizard.booking_api.model.EventDelivery;
import com.stackwizard.booking_api.model.EventSubscription;
import com.stackwizard.booking_api.model.IntegrationEvent;
import com.stackwizard.booking_api.repository.EventDeliveryRepository;
import com.stackwizard.booking_api.repository.EventSubscriptionRepository;
import com.stackwizard.booking_api.repository.IntegrationEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

@Service
public class EventSubscriptionService {
    private final EventSubscriptionRepository subscriptionRepo;
    private final IntegrationEventRepository eventRepo;
    private final EventDeliveryRepository deliveryRepo;
    private final SecureRandom random = new SecureRandom();

    public EventSubscriptionService(EventSubscriptionRepository subscriptionRepo,
                                    IntegrationEventRepository eventRepo,
                                    EventDeliveryRepository deliveryRepo) {
        this.subscriptionRepo = subscriptionRepo;
        this.eventRepo = eventRepo;
        this.deliveryRepo = deliveryRepo;
    }

    public List<EventSubscription> list(Long tenantId) {
        return subscriptionRepo.findByTenantIdOrderByIdAsc(tenantId);
    }

    @Transactional
    public EventSubscription create(Long tenantId, String name, String endpointUrl, List<String> eventTypes) {
        EventSubscription sub = EventSubscription.builder()
                .tenantId(tenantId)
                .transport(EventSubscription.Transport.WEBHOOK)
                .secret(newSecret())
                .active(true)
                .build();
        apply(sub, name, endpointUrl, eventTypes, true);
        return subscriptionRepo.save(sub);
    }

    @Transactional
    public EventSubscription update(Long tenantId, Long id, String name, String endpointUrl, List<String> eventTypes, Boolean active) {
        EventSubscription sub = require(tenantId, id);
        apply(sub, name, endpointUrl, eventTypes, false);
        if (active != null) {
            sub.setActive(active);
        }
        return subscriptionRepo.save(sub);
    }

    @Transactional
    public EventSubscription rotateSecret(Long tenantId, Long id) {
        EventSubscription sub = require(tenantId, id);
        sub.setSecret(newSecret());
        sub.setUpdatedAt(OffsetDateTime.now());
        return subscriptionRepo.save(sub);
    }

    @Transactional
    public void delete(Long tenantId, Long id) {
        subscriptionRepo.delete(require(tenantId, id));
    }

    public Page<IntegrationEvent> events(Long tenantId, String type, Pageable pageable) {
        return eventRepo.search(tenantId, StringUtils.hasText(type) ? type.trim() : null, pageable);
    }

    public List<EventDelivery> deliveries(Long tenantId, Long eventId) {
        requireEvent(tenantId, eventId);
        return deliveryRepo.findByEventIdOrderByIdAsc(eventId);
    }

    /** Re-queues every delivery of the event (DEAD or SENT) for an immediate attempt. */
    @Transactional
    public List<EventDelivery> replay(Long tenantId, Long eventId) {
        requireEvent(tenantId, eventId);
        List<EventDelivery> deliveries = deliveryRepo.findByEventIdOrderByIdAsc(eventId);
        OffsetDateTime now = OffsetDateTime.now();
        for (EventDelivery d : deliveries) {
            d.setStatus(EventDelivery.Status.PENDING);
            d.setAttempts(0);
            d.setNextAttemptAt(now);
            d.setLeaseUntil(null);
        }
        return deliveryRepo.saveAll(deliveries);
    }

    private void apply(EventSubscription sub, String name, String endpointUrl, List<String> eventTypes, boolean create) {
        if (create || name != null) {
            if (!StringUtils.hasText(name)) {
                throw new IllegalArgumentException("name is required");
            }
            sub.setName(name.trim());
        }
        if (create || endpointUrl != null) {
            sub.setEndpointUrl(validUrl(endpointUrl));
        }
        if (create || eventTypes != null) {
            List<String> types = eventTypes == null ? List.of() : eventTypes.stream()
                    .filter(StringUtils::hasText).map(String::trim).toList();
            sub.setEventTypes(types.isEmpty() ? new String[]{"*"} : types.toArray(String[]::new));
        }
        sub.setUpdatedAt(OffsetDateTime.now());
    }

    private static String validUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("endpointUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("endpointUrl is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("endpointUrl must be http(s)");
        }
        return uri.toString();
    }

    private EventSubscription require(Long tenantId, Long id) {
        return subscriptionRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));
    }

    private void requireEvent(Long tenantId, Long eventId) {
        eventRepo.findByIdAndTenantId(eventId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found"));
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
