package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.LoyaltyDtos;
import com.stackwizard.booking_api.model.EventDelivery;
import com.stackwizard.booking_api.model.EventSubscription;
import com.stackwizard.booking_api.model.IntegrationEvent;
import com.stackwizard.booking_api.security.LoyaltyAccess;
import com.stackwizard.booking_api.security.LoyaltyPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.integration.EventSubscriptionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Outbox subscriptions (webhooks for an external e-mail/CRM service), event log and replay. ORG level. */
@RestController
@RequestMapping("/api/integration")
public class IntegrationEventController {
    private final EventSubscriptionService service;
    private final LoyaltyAccess access;

    public IntegrationEventController(EventSubscriptionService service, LoyaltyAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/subscriptions")
    public List<EventSubscription> subscriptions() {
        access.require(LoyaltyPermission.INTEGRATION_CONFIG);
        return service.list(TenantResolver.requireOrgTenantId());
    }

    @PostMapping("/subscriptions")
    public EventSubscription create(@RequestBody LoyaltyDtos.SubscriptionRequest body) {
        access.require(LoyaltyPermission.INTEGRATION_CONFIG);
        return service.create(TenantResolver.requireOrgTenantId(), body.name(), body.endpointUrl(), body.eventTypes());
    }

    @PutMapping("/subscriptions/{id}")
    public EventSubscription update(@PathVariable Long id, @RequestBody LoyaltyDtos.SubscriptionRequest body) {
        access.require(LoyaltyPermission.INTEGRATION_CONFIG);
        return service.update(TenantResolver.requireOrgTenantId(), id, body.name(), body.endpointUrl(), body.eventTypes(), body.active());
    }

    @PostMapping("/subscriptions/{id}/rotate-secret")
    public EventSubscription rotate(@PathVariable Long id) {
        access.require(LoyaltyPermission.INTEGRATION_CONFIG);
        return service.rotateSecret(TenantResolver.requireOrgTenantId(), id);
    }

    @DeleteMapping("/subscriptions/{id}")
    public void delete(@PathVariable Long id) {
        access.require(LoyaltyPermission.INTEGRATION_CONFIG);
        service.delete(TenantResolver.requireOrgTenantId(), id);
    }

    @GetMapping("/events")
    public Page<IntegrationEvent> events(@RequestParam(required = false) String type,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        access.require(LoyaltyPermission.INTEGRATION_CONFIG);
        return service.events(TenantResolver.requireOrgTenantId(), type, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
    }

    @GetMapping("/events/{eventId}/deliveries")
    public List<EventDelivery> deliveries(@PathVariable Long eventId) {
        access.require(LoyaltyPermission.INTEGRATION_CONFIG);
        return service.deliveries(TenantResolver.requireOrgTenantId(), eventId);
    }

    @PostMapping("/events/{eventId}/replay")
    public List<EventDelivery> replay(@PathVariable Long eventId) {
        access.require(LoyaltyPermission.INTEGRATION_CONFIG);
        return service.replay(TenantResolver.requireOrgTenantId(), eventId);
    }
}
