package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.LoyaltyDtos;
import com.stackwizard.booking_api.model.PmsReservation;
import com.stackwizard.booking_api.security.LoyaltyAccess;
import com.stackwizard.booking_api.security.LoyaltyPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.pms.PmsReservationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PMS reservations as the platform sees them: listing, match queue, manual matching, loyalty re-evaluation. */
@RestController
@RequestMapping("/api/pms/reservations")
public class PmsReservationController {
    private final PmsReservationService service;
    private final LoyaltyAccess access;

    public PmsReservationController(PmsReservationService service, LoyaltyAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public Page<PmsReservation> list(@RequestParam(required = false) PmsReservation.Status status,
                                     @RequestParam(required = false) Long propertyTenantId,
                                     @RequestParam(required = false) Long contactId,
                                     @RequestParam(defaultValue = "false") boolean unmatched,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        access.require(LoyaltyPermission.READ);
        return service.search(TenantResolver.requireOrgTenantId(), status, propertyTenantId, contactId, unmatched, q,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
    }

    @GetMapping("/{id}")
    public PmsReservationService.ReservationView get(@PathVariable Long id) {
        access.require(LoyaltyPermission.READ);
        return service.view(TenantResolver.requireOrgTenantId(), id);
    }

    @PostMapping("/{id}/match")
    public PmsReservationService.ReservationView match(@PathVariable Long id, @RequestBody LoyaltyDtos.MatchRequest body) {
        access.require(LoyaltyPermission.ADJUST);
        return service.matchManually(TenantResolver.requireOrgTenantId(), id, body.contactId());
    }

    @PostMapping("/{id}/reevaluate")
    public PmsReservationService.ReservationView reevaluate(@PathVariable Long id) {
        access.require(LoyaltyPermission.ADJUST);
        return service.reevaluate(TenantResolver.requireOrgTenantId(), id);
    }
}
