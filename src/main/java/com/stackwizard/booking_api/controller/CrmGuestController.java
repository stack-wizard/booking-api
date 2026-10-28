package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.LoyaltyDtos;
import com.stackwizard.booking_api.model.CrmAccountProduction;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.CrmContactConsent;
import com.stackwizard.booking_api.model.CrmContactIdentifier;
import com.stackwizard.booking_api.model.CrmContactMembership;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.PmsReservation;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.crm.CrmContactIdentityService;
import com.stackwizard.booking_api.service.crm.CrmGuestStatsService;
import com.stackwizard.booking_api.service.pms.PmsReservationService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Guest 360 for CRM: the person's keys, cards, consents, stays and spend; account production; duplicate merge. */
@RestController
@RequestMapping("/api/crm")
public class CrmGuestController {
    private final CrmContactRepository contactRepo;
    private final CrmAccountRepository accountRepo;
    private final LoyaltyMemberRepository memberRepo;
    private final CrmContactIdentityService identity;
    private final CrmGuestStatsService stats;
    private final PmsReservationService reservations;
    private final CrmAccessContext access;

    public CrmGuestController(CrmContactRepository contactRepo,
                              CrmAccountRepository accountRepo,
                              LoyaltyMemberRepository memberRepo,
                              CrmContactIdentityService identity,
                              CrmGuestStatsService stats,
                              PmsReservationService reservations,
                              CrmAccessContext access) {
        this.contactRepo = contactRepo;
        this.accountRepo = accountRepo;
        this.memberRepo = memberRepo;
        this.identity = identity;
        this.stats = stats;
        this.reservations = reservations;
        this.access = access;
    }

    public record GuestView(CrmContact contact, List<CrmContactIdentifier> identifiers, List<CrmContactMembership> memberships,
                            List<CrmContactConsent> consents, List<LoyaltyMember> loyaltyMemberships) {
    }

    @GetMapping("/contacts/{id}/guest")
    public GuestView guest(@PathVariable Long id) {
        access.require(CrmPermission.ACCOUNT_READ);
        CrmContact c = require(id);
        return new GuestView(c, identity.identifiers(c.getId()), identity.memberships(c.getId()), identity.consents(c.getId()),
                memberRepo.findByContactIdOrderByIdAsc(c.getId()));
    }

    @GetMapping("/contacts/{id}/stays")
    public List<PmsReservation> stays(@PathVariable Long id) {
        access.require(CrmPermission.ACCOUNT_READ);
        return reservations.forContact(TenantResolver.requireOrgTenantId(), id);
    }

    @PostMapping("/contacts/{id}/merge")
    @Transactional
    public CrmContact merge(@PathVariable Long id, @RequestBody LoyaltyDtos.MergeRequest body) {
        access.require(CrmPermission.ACCOUNT_WRITE);
        return identity.merge(TenantResolver.requireOrgTenantId(), body.fromContactId(), id, body.reason(), access.currentUserId());
    }

    @PostMapping("/contacts/{id}/recompute-stats")
    @Transactional
    public CrmContact recompute(@PathVariable Long id) {
        access.require(CrmPermission.ACCOUNT_READ);
        CrmContact c = require(id);
        stats.recomputeContact(c.getId());
        return require(id);
    }

    @GetMapping("/accounts/{id}/production")
    public List<CrmAccountProduction> production(@PathVariable Long id) {
        access.require(CrmPermission.ACCOUNT_READ);
        accountRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId()).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        return stats.production(id);
    }

    @PostMapping("/accounts/{id}/recompute-production")
    @Transactional
    public List<CrmAccountProduction> recomputeProduction(@PathVariable Long id) {
        access.require(CrmPermission.ACCOUNT_READ);
        var account = accountRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId()).orElseThrow(() -> new IllegalArgumentException("Account not found"));
        stats.recomputeAccount(account);
        return stats.production(id);
    }

    private CrmContact require(Long id) {
        return contactRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId()).orElseThrow(() -> new IllegalArgumentException("Contact not found"));
    }
}
