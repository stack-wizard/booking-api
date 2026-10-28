package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyMemberIdentifier;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;
import com.stackwizard.booking_api.model.LoyaltyTierHistory;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberIdentifierRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes one migrated member: person, membership with the original card number, identifiers, consents, opening
 * balance and the tier from the source system. Runs through the same ledger and tier engine as live postings.
 */
@Service
public class LoyaltyMemberImportSupport {
    private final LoyaltyMemberRepository memberRepo;
    private final LoyaltyMemberIdentifierRepository identifierRepo;
    private final LoyaltyLedgerRepository ledgerRepo;
    private final LoyaltyTierRepository tierRepo;
    private final CrmContactRepository contactRepo;
    private final LoyaltyMemberService memberService;
    private final LoyaltyLedgerService ledgerService;
    private final LoyaltyTierEngine tierEngine;
    private final LoyaltyProgramService programService;
    private final LoyaltyEvents events;
    private final Clock clock;

    public LoyaltyMemberImportSupport(LoyaltyMemberRepository memberRepo,
                                      LoyaltyMemberIdentifierRepository identifierRepo,
                                      LoyaltyLedgerRepository ledgerRepo,
                                      LoyaltyTierRepository tierRepo,
                                      CrmContactRepository contactRepo,
                                      LoyaltyMemberService memberService,
                                      LoyaltyLedgerService ledgerService,
                                      LoyaltyTierEngine tierEngine,
                                      LoyaltyProgramService programService,
                                      LoyaltyEvents events,
                                      Clock loyaltyClock) {
        this.memberRepo = memberRepo;
        this.identifierRepo = identifierRepo;
        this.ledgerRepo = ledgerRepo;
        this.tierRepo = tierRepo;
        this.contactRepo = contactRepo;
        this.memberService = memberService;
        this.ledgerService = ledgerService;
        this.tierEngine = tierEngine;
        this.programService = programService;
        this.events = events;
        this.clock = loyaltyClock;
    }

    public record ImportedMember(String memberNumber, String firstName, String lastName, String email, String phone,
                                 String country, String city, String postalCode, String address, LocalDate dateOfBirth,
                                 String gender, String language, String referrerNumber, Map<String, Boolean> consents,
                                 List<String> interests, LocalDate activatedAt, OffsetDateTime walletAddedAt,
                                 String tier, LocalDate tierValidUntil, Integer nightsTotal, Integer nightsCurrentYear,
                                 LocalDate lastCheckout, boolean deactivated, LocalDate asOf, Long batchId) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long importMember(Long tenantId, Long programId, ImportedMember in, boolean suppressEvents) {
        LoyaltyMemberService.MemberInput profile = new LoyaltyMemberService.MemberInput(in.firstName(), in.lastName(), in.email(),
                in.phone(), in.country(), in.city(), in.postalCode(), in.address(), in.dateOfBirth(), in.gender(), in.language(),
                null, null, in.interests());
        OffsetDateTime now = OffsetDateTime.now(clock);
        LoyaltyMember member = memberRepo.findByProgramIdAndMemberNumber(programId, in.memberNumber()).orElse(null);
        boolean created = member == null;
        if (created) {
            CrmContact contact = memberService.upsertContact(tenantId, profile, in.email());
            memberRepo.findByProgramIdAndContactId(programId, contact.getId()).ifPresent(other -> {
                throw new IllegalStateException("person " + in.email() + " is already member " + other.getMemberNumber());
            });
            member = memberRepo.save(LoyaltyMember.builder()
                    .tenantId(tenantId)
                    .programId(programId)
                    .contactId(contact.getId())
                    .memberNumber(in.memberNumber())
                    .status(LoyaltyMember.Status.ACTIVE)
                    .activatedAt(in.activatedAt() != null ? in.activatedAt().atStartOfDay(clock.getZone()).toOffsetDateTime() : now)
                    .tierOverride(false)
                    .nightsTotal(0).nightsCurrentYear(0).nights24m(0)
                    .source(LoyaltyMember.Source.IMPORT)
                    .externalRef("import:" + in.batchId())
                    .attrs(memberService.profileAttrs(profile))
                    .updatedAt(now)
                    .build());
            memberService.addIdentifier(member, LoyaltyMemberIdentifier.Type.CARD, member.getMemberNumber(), true);
            memberService.addIdentifier(member, LoyaltyMemberIdentifier.Type.EMAIL, in.email(), true);
        } else {
            member = memberRepo.lockById(member.getId()).orElseThrow();
            CrmContact contact = contactRepo.findById(member.getContactId()).orElseThrow();
            contact.setFirstName(in.firstName());
            contact.setLastName(in.lastName());
            if (in.phone() != null) {
                contact.setPhone(in.phone());
            }
            if (!in.email().equalsIgnoreCase(contact.getEmail())) {
                contact.setEmail(in.email());
                LoyaltyMember m = member;
                if (identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, LoyaltyMemberIdentifier.Type.EMAIL, in.email()).isEmpty()) {
                    identifierRepo.findByMemberIdOrderByIdAsc(m.getId()).stream()
                            .filter(i -> i.getType() == LoyaltyMemberIdentifier.Type.EMAIL && Boolean.TRUE.equals(i.getIsPrimary()))
                            .forEach(i -> {
                                i.setIsPrimary(false);
                                identifierRepo.save(i);
                            });
                    memberService.addIdentifier(m, LoyaltyMemberIdentifier.Type.EMAIL, in.email(), true);
                }
            }
            contactRepo.save(contact);
            ObjectNode attrs = (ObjectNode) member.getAttrs();
            attrs.setAll(memberService.profileAttrs(profile));
            if (in.activatedAt() != null) {
                member.setActivatedAt(in.activatedAt().atStartOfDay(clock.getZone()).toOffsetDateTime());
            }
        }
        String phone = LoyaltyNormalizer.phone(in.phone());
        if (phone != null && identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, LoyaltyMemberIdentifier.Type.PHONE, phone).isEmpty()) {
            memberService.addIdentifier(member, LoyaltyMemberIdentifier.Type.PHONE, phone, true);
        }
        if (in.walletAddedAt() != null && member.getWalletAddedAt() == null) {
            // the source system's night total already contains any wallet bonus: no new bonus entry
            member.setWalletAddedAt(in.walletAddedAt());
        }
        if (in.referrerNumber() != null && member.getReferrerMemberId() == null) {
            LoyaltyMember self = member;
            memberRepo.findByProgramIdAndMemberNumber(programId, in.referrerNumber())
                    .filter(r -> !r.getId().equals(self.getId()))
                    .ifPresent(r -> self.setReferrerMemberId(r.getId()));
        }
        if (in.lastCheckout() != null && (member.getLastStayCheckout() == null || in.lastCheckout().isAfter(member.getLastStayCheckout()))) {
            member.setLastStayCheckout(in.lastCheckout());
        }
        if (in.deactivated()) {
            member.setStatus(LoyaltyMember.Status.DEACTIVATED);
        }
        member.setUpdatedAt(now);
        memberRepo.save(member);
        memberService.applyConsents(member, in.consents(), "IMPORT");

        LoyaltyRuleSet rs = programService.ruleSetOn(programId, LocalDate.now(clock));
        replaceOpeningBalance(member, rs, in);

        if (in.tier() != null) {
            LoyaltyTier tier = tierRepo.findByRuleSetIdOrderByRankAsc(rs.getId()).stream()
                    .filter(t -> t.getCode().equalsIgnoreCase(in.tier()) || t.getName().equalsIgnoreCase(in.tier()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown tier " + in.tier()));
            ledgerService.setTier(member, tier, in.tierValidUntil(), LoyaltyTierHistory.Reason.MIGRATION, null,
                    "import batch " + in.batchId(), true);
            // the file's tier equals what the nights give: no override needed, the engine owns it from here
            if (Objects.equals(member.getComputedTierId(), member.getAssignedTierId())) {
                member.setTierOverride(false);
                memberRepo.save(member);
            }
        } else {
            tierEngine.recompute(member, new LoyaltyTierEngine.Context(LoyaltyTierHistory.Reason.MIGRATION, null,
                    "import batch " + in.batchId(), true));
        }
        if (created && !suppressEvents) {
            events.publish(LoyaltyEvents.MEMBER_REGISTERED, member, events.data().put("source", "IMPORT"));
        }
        return member.getId();
    }

    /**
     * Opening balance as two entries so calendar-year counters stay right: this year's nights dated {@code asOf},
     * the rest dated 31.12. of the previous year. A re-import reverses the previous opening entries first.
     */
    private void replaceOpeningBalance(LoyaltyMember member, LoyaltyRuleSet rs, ImportedMember in) {
        if (in.nightsTotal() == null) {
            return;
        }
        int total = in.nightsTotal();
        int thisYear = in.nightsCurrentYear() == null ? 0 : Math.min(in.nightsCurrentYear(), total);
        LocalDate asOf = in.asOf();
        List<LoyaltyLedgerEntry> existing = ledgerRepo.findActiveBalanceEntries(member.getId()).stream()
                .filter(e -> e.getSource() == LoyaltyLedgerEntry.Source.OPENING_BALANCE)
                .toList();
        int existingTotal = existing.stream().mapToInt(LoyaltyLedgerEntry::getQty).sum();
        int existingThisYear = existing.stream().filter(e -> e.getAsOf().getYear() == asOf.getYear()).mapToInt(LoyaltyLedgerEntry::getQty).sum();
        if (existingTotal == total && existingThisYear == thisYear && !existing.isEmpty()) {
            return;
        }
        for (LoyaltyLedgerEntry e : existing) {
            ledgerService.reverseEntry(e, "opening balance replaced by import " + in.batchId(), null);
        }
        if (total - thisYear > 0) {
            ledgerService.append(opening(member, rs, total - thisYear, LocalDate.of(asOf.getYear() - 1, 12, 31), in.batchId()));
        }
        if (thisYear > 0) {
            ledgerService.append(opening(member, rs, thisYear, asOf, in.batchId()));
        }
    }

    private static LoyaltyLedgerEntry opening(LoyaltyMember member, LoyaltyRuleSet rs, int qty, LocalDate asOf, Long batchId) {
        return LoyaltyLedgerEntry.builder()
                .tenantId(member.getTenantId())
                .memberId(member.getId())
                .metric(LoyaltyLedgerEntry.Metric.QUALIFYING_NIGHT)
                .qty(qty)
                .asOf(asOf)
                .source(LoyaltyLedgerEntry.Source.OPENING_BALANCE)
                .ruleSetId(rs.getId())
                .reason("opening balance, import batch " + batchId)
                .build();
    }
}
