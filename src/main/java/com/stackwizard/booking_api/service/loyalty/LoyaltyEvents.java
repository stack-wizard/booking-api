package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.CrmContactConsent;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyTier;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmContactConsentRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierRepository;
import com.stackwizard.booking_api.service.integration.IntegrationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Loyalty side of the outbox. Every payload carries a member snapshot (name, e-mail, language, consents, tier)
 * so the external e-mail service can act without calling back.
 */
@Component
public class LoyaltyEvents {
    public static final String MEMBER_REGISTERED = "loyalty.member.registered";
    public static final String MEMBER_UPDATED = "loyalty.member.updated";
    public static final String MEMBER_DEACTIVATED = "loyalty.member.deactivated";
    public static final String MEMBER_WALLET_ADDED = "loyalty.member.wallet_added";
    public static final String STAY_POSTED = "loyalty.stay.posted";
    public static final String STAY_REJECTED = "loyalty.stay.rejected";
    public static final String STAY_REVERSED = "loyalty.stay.reversed";
    public static final String TIER_UPGRADED = "loyalty.tier.upgraded";
    public static final String TIER_DOWNGRADED = "loyalty.tier.downgraded";
    public static final String TIER_EXPIRING = "loyalty.tier.expiring";
    public static final String TIER_NEAR_NEXT = "loyalty.tier.near_next";
    public static final String ADJUSTMENT_CREATED = "loyalty.adjustment.created";

    private final IntegrationEventPublisher publisher;
    private final CrmContactRepository contactRepo;
    private final CrmContactConsentRepository consentRepo;
    private final LoyaltyTierRepository tierRepo;

    public LoyaltyEvents(IntegrationEventPublisher publisher,
                         CrmContactRepository contactRepo,
                         CrmContactConsentRepository consentRepo,
                         LoyaltyTierRepository tierRepo) {
        this.publisher = publisher;
        this.contactRepo = contactRepo;
        this.consentRepo = consentRepo;
        this.tierRepo = tierRepo;
    }

    public ObjectNode data() {
        return publisher.newPayload();
    }

    public void publish(String type, LoyaltyMember member, ObjectNode data) {
        ObjectNode payload = data == null ? publisher.newPayload() : data;
        payload.set("member", snapshot(member));
        publisher.publish(member.getTenantId(), type, "loyalty_member", member.getId(), payload);
    }

    ObjectNode snapshot(LoyaltyMember member) {
        ObjectNode node = publisher.newPayload();
        node.put("id", member.getId());
        node.put("number", member.getMemberNumber());
        node.put("status", member.getStatus().name());
        CrmContact contact = contactRepo.findById(member.getContactId()).orElse(null);
        if (contact != null) {
            node.put("firstName", contact.getFirstName());
            node.put("lastName", contact.getLastName());
            node.put("email", contact.getEmail());
            node.put("phone", contact.getPhone());
        }
        if (contact != null) {
            node.put("contactId", contact.getId());
            node.put("language", contact.getLanguage());
            node.put("country", contact.getCountry());
        }
        LoyaltyTier tier = member.getAssignedTierId() == null ? null : tierRepo.findById(member.getAssignedTierId()).orElse(null);
        if (tier != null) {
            node.put("tier", tier.getCode());
            node.put("tierName", tier.getName());
        }
        node.put("tierValidUntil", member.getTierValidUntil() == null ? null : member.getTierValidUntil().toString());
        node.put("nightsTotal", member.getNightsTotal());
        node.put("nightsCurrentYear", member.getNightsCurrentYear());
        node.put("walletAdded", member.getWalletAddedAt() != null);
        ObjectNode consents = node.putObject("consents");
        for (CrmContactConsent c : consentRepo.findByContactIdOrderByChannelAsc(member.getContactId())) {
            consents.put(c.getChannel().name().toLowerCase(), Boolean.TRUE.equals(c.getGranted()));
        }
        return node;
    }
}
