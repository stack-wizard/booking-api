package com.stackwizard.booking_api.service.loyalty;

import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyMemberIdentifier;
import com.stackwizard.booking_api.model.LoyaltyReservation;
import com.stackwizard.booking_api.repository.LoyaltyMemberIdentifierRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reservation → member: card number on the reservation first (FR-04.1), then the holder's e-mail against primary
 * and secondary member e-mails. A manual match is never overwritten by an import.
 */
@Service
public class LoyaltyMatchingService {
    private final LoyaltyMemberRepository memberRepo;
    private final LoyaltyMemberIdentifierRepository identifierRepo;

    public LoyaltyMatchingService(LoyaltyMemberRepository memberRepo, LoyaltyMemberIdentifierRepository identifierRepo) {
        this.memberRepo = memberRepo;
        this.identifierRepo = identifierRepo;
    }

    public record Match(LoyaltyMember member, LoyaltyReservation.MatchMethod method) {
    }

    public Optional<Match> match(LoyaltyReservation r) {
        if (r.getMatchMethod() == LoyaltyReservation.MatchMethod.MANUAL && r.getMemberId() != null) {
            return memberRepo.findById(r.getMemberId()).map(m -> new Match(m, LoyaltyReservation.MatchMethod.MANUAL));
        }
        String card = LoyaltyNormalizer.text(r.getMembershipNo());
        if (card != null) {
            Optional<LoyaltyMember> byCard = memberRepo.findByProgramIdAndMemberNumber(r.getProgramId(), card);
            if (byCard.isPresent()) {
                return byCard.map(m -> new Match(m, LoyaltyReservation.MatchMethod.MEMBERSHIP_NO));
            }
        }
        String email = LoyaltyNormalizer.email(r.getHolderEmailNorm());
        if (email != null) {
            Optional<LoyaltyMemberIdentifier> id = identifierRepo.findByTenantIdAndTypeAndValueNorm(r.getTenantId(), LoyaltyMemberIdentifier.Type.EMAIL, email);
            if (id.isPresent()) {
                LoyaltyReservation.MatchMethod method = Boolean.TRUE.equals(id.get().getIsPrimary())
                        ? LoyaltyReservation.MatchMethod.EMAIL : LoyaltyReservation.MatchMethod.SECONDARY_EMAIL;
                return memberRepo.findById(id.get().getMemberId())
                        .filter(m -> m.getProgramId().equals(r.getProgramId()))
                        .map(m -> new Match(m, method));
            }
        }
        return Optional.empty();
    }
}
