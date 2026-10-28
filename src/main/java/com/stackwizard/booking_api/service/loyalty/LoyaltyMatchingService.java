package com.stackwizard.booking_api.service.loyalty;

import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.CrmContactIdentifier;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.PmsReservation;
import com.stackwizard.booking_api.repository.CrmContactIdentifierRepository;
import com.stackwizard.booking_api.repository.CrmContactMembershipRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reservation → person (crm_contact). Order of trust: the PMS profile id on the reservation, then a membership card
 * number, then e-mail (primary, then secondary), then phone (FR-04.1). A manual match is never overwritten.
 * Loyalty then asks whether that person is a member of the programme.
 */
@Service
public class LoyaltyMatchingService {
    private final CrmContactRepository contactRepo;
    private final CrmContactIdentifierRepository identifierRepo;
    private final CrmContactMembershipRepository membershipRepo;
    private final LoyaltyMemberRepository memberRepo;

    public LoyaltyMatchingService(CrmContactRepository contactRepo,
                                  CrmContactIdentifierRepository identifierRepo,
                                  CrmContactMembershipRepository membershipRepo,
                                  LoyaltyMemberRepository memberRepo) {
        this.contactRepo = contactRepo;
        this.identifierRepo = identifierRepo;
        this.membershipRepo = membershipRepo;
        this.memberRepo = memberRepo;
    }

    public record Match(CrmContact contact, PmsReservation.MatchMethod method) {
    }

    public Optional<Match> matchContact(PmsReservation r) {
        if (r.getMatchMethod() == PmsReservation.MatchMethod.MANUAL && r.getContactId() != null) {
            return contactRepo.findById(r.getContactId()).map(c -> new Match(resolveMerged(c), PmsReservation.MatchMethod.MANUAL));
        }
        Long tenantId = r.getTenantId();
        String profileId = LoyaltyNormalizer.text(r.getHolderPmsProfileId());
        if (profileId != null) {
            Optional<CrmContact> byProfile = contactRepo.findByTenantIdAndOperaProfileId(tenantId, profileId)
                    .or(() -> byIdentifier(tenantId, CrmContactIdentifier.Type.OPERA_PROFILE, profileId));
            if (byProfile.isPresent()) {
                return byProfile.map(c -> new Match(resolveMerged(c), PmsReservation.MatchMethod.PMS_PROFILE));
            }
        }
        String card = LoyaltyNormalizer.text(r.getMembershipNo());
        if (card != null) {
            Optional<CrmContact> byCard = byIdentifier(tenantId, CrmContactIdentifier.Type.CARD, card)
                    .or(() -> membershipRepo.findFirstByTenantIdAndCardNo(tenantId, card).flatMap(m -> contactRepo.findById(m.getContactId())));
            if (byCard.isPresent()) {
                return byCard.map(c -> new Match(resolveMerged(c), PmsReservation.MatchMethod.MEMBERSHIP_NO));
            }
        }
        String email = LoyaltyNormalizer.email(r.getHolderEmailNorm());
        if (email != null) {
            Optional<CrmContactIdentifier> id = identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, CrmContactIdentifier.Type.EMAIL, email);
            if (id.isPresent()) {
                PmsReservation.MatchMethod method = Boolean.TRUE.equals(id.get().getIsPrimary())
                        ? PmsReservation.MatchMethod.EMAIL : PmsReservation.MatchMethod.SECONDARY_EMAIL;
                return contactRepo.findById(id.get().getContactId()).map(c -> new Match(resolveMerged(c), method));
            }
            Optional<CrmContact> byContactEmail = contactRepo.findFirstByTenantIdAndEmailIgnoreCaseOrderByIdAsc(tenantId, email);
            if (byContactEmail.isPresent()) {
                return byContactEmail.map(c -> new Match(resolveMerged(c), PmsReservation.MatchMethod.EMAIL));
            }
        }
        String phone = LoyaltyNormalizer.phone(r.getHolderPhoneNorm());
        if (phone != null) {
            Optional<CrmContact> byPhone = byIdentifier(tenantId, CrmContactIdentifier.Type.PHONE, phone);
            if (byPhone.isPresent()) {
                return byPhone.map(c -> new Match(resolveMerged(c), PmsReservation.MatchMethod.PHONE));
            }
        }
        return Optional.empty();
    }

    public Optional<LoyaltyMember> memberFor(Long programId, Long contactId) {
        if (programId == null || contactId == null) {
            return Optional.empty();
        }
        return memberRepo.findByProgramIdAndContactId(programId, contactId);
    }

    private Optional<CrmContact> byIdentifier(Long tenantId, CrmContactIdentifier.Type type, String value) {
        return identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, type, value).flatMap(i -> contactRepo.findById(i.getContactId()));
    }

    /** A merged profile points at its survivor; follow the chain so old identifiers still land on the right person. */
    private CrmContact resolveMerged(CrmContact c) {
        CrmContact current = c;
        for (int i = 0; i < 10 && current.getMergedIntoId() != null; i++) {
            CrmContact next = contactRepo.findById(current.getMergedIntoId()).orElse(null);
            if (next == null) {
                break;
            }
            current = next;
        }
        return current;
    }
}
