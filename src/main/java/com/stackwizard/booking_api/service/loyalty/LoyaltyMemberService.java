package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.CrmContactConsent;
import com.stackwizard.booking_api.model.CrmContactIdentifier;
import com.stackwizard.booking_api.model.CrmContactMembership;
import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyProgram;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;
import com.stackwizard.booking_api.model.LoyaltyTierHistory;
import com.stackwizard.booking_api.repository.CrmContactIdentifierRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyProgramRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierHistoryRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierRepository;
import com.stackwizard.booking_api.service.crm.CrmContactIdentityService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Member lifecycle: enrolment, profile, wallet flag, deactivation, anonymisation. Identity lives on the contact. */
@Service
public class LoyaltyMemberService {
    private final LoyaltyMemberRepository memberRepo;
    private final LoyaltyProgramRepository programRepo;
    private final CrmContactIdentifierRepository identifierRepo;
    private final LoyaltyTierRepository tierRepo;
    private final LoyaltyTierHistoryRepository historyRepo;
    private final LoyaltyLedgerRepository ledgerRepo;
    private final CrmContactRepository contactRepo;
    private final CrmContactIdentityService identity;
    private final LoyaltyProgramService programService;
    private final LoyaltyLedgerService ledgerService;
    private final LoyaltyTierEngine tierEngine;
    private final LoyaltyEvents events;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoyaltyMemberService(LoyaltyMemberRepository memberRepo,
                                LoyaltyProgramRepository programRepo,
                                CrmContactIdentifierRepository identifierRepo,
                                LoyaltyTierRepository tierRepo,
                                LoyaltyTierHistoryRepository historyRepo,
                                LoyaltyLedgerRepository ledgerRepo,
                                CrmContactRepository contactRepo,
                                CrmContactIdentityService identity,
                                LoyaltyProgramService programService,
                                LoyaltyLedgerService ledgerService,
                                LoyaltyTierEngine tierEngine,
                                LoyaltyEvents events,
                                Clock loyaltyClock) {
        this.memberRepo = memberRepo;
        this.programRepo = programRepo;
        this.identifierRepo = identifierRepo;
        this.tierRepo = tierRepo;
        this.historyRepo = historyRepo;
        this.ledgerRepo = ledgerRepo;
        this.contactRepo = contactRepo;
        this.identity = identity;
        this.programService = programService;
        this.ledgerService = ledgerService;
        this.tierEngine = tierEngine;
        this.events = events;
        this.clock = loyaltyClock;
    }

    public record MemberInput(String firstName, String lastName, String email, String phone, String country, String city,
                              String postalCode, String address, LocalDate dateOfBirth, String gender, String language,
                              String referrerNumber, Map<String, Boolean> consents, List<String> interests) {
    }

    public record Progress(String currentTier, String currentTierName, String nextTier, Integer nextThreshold,
                           Integer nightsToNext, int tierNights) {
    }

    /** One row of the member list: what the back office needs without opening the member. */
    public record MemberRow(Long id, Long contactId, String memberNumber, String firstName, String lastName, String email, String tierCode,
                            String tierName, Integer nightsTotal, Integer nightsCurrentYear, LocalDate tierValidUntil,
                            boolean walletAdded, boolean walletException, LoyaltyMember.Status status) {
    }

    public record MemberView(LoyaltyMember member, CrmContact contact, LoyaltyTier tier, Progress progress,
                             List<CrmContactIdentifier> identifiers, List<CrmContactMembership> memberships,
                             List<CrmContactConsent> consents) {
    }

    public Page<MemberRow> search(Long tenantId, Long programId, String q, String email, String number, Long tierId, Pageable pageable) {
        programService.require(tenantId, programId);
        Page<LoyaltyMember> page = memberRepo.search(tenantId, programId, blankToNull(q), blankToNull(email), blankToNull(number), tierId, pageable);
        Map<Long, CrmContact> contacts = new java.util.HashMap<>();
        contactRepo.findAllById(page.getContent().stream().map(LoyaltyMember::getContactId).toList()).forEach(c -> contacts.put(c.getId(), c));
        Map<Long, LoyaltyTier> tiers = new java.util.HashMap<>();
        tierRepo.findAllById(page.getContent().stream().map(LoyaltyMember::getAssignedTierId).filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(t -> tiers.put(t.getId(), t));
        return page.map(m -> {
            CrmContact c = contacts.get(m.getContactId());
            LoyaltyTier t = m.getAssignedTierId() == null ? null : tiers.get(m.getAssignedTierId());
            return new MemberRow(m.getId(), m.getContactId(), m.getMemberNumber(), c == null ? null : c.getFirstName(), c == null ? null : c.getLastName(),
                    c == null ? null : c.getEmail(), t == null ? null : t.getCode(), t == null ? null : t.getName(), m.getNightsTotal(),
                    m.getNightsCurrentYear(), m.getTierValidUntil(), m.getWalletAddedAt() != null, m.getWalletExceptionReason() != null,
                    m.getStatus());
        });
    }

    public LoyaltyMember require(Long tenantId, Long memberId) {
        return memberRepo.findByIdAndTenantId(memberId, tenantId).orElseThrow(() -> new IllegalArgumentException("Member not found"));
    }

    public MemberView view(Long tenantId, Long memberId) {
        LoyaltyMember m = require(tenantId, memberId);
        CrmContact contact = contactRepo.findById(m.getContactId()).orElse(null);
        LoyaltyRuleSet rs = programService.ruleSetOn(m.getProgramId(), LocalDate.now(clock));
        List<LoyaltyTier> tiers = tierRepo.findByRuleSetIdOrderByRankAsc(rs.getId());
        LoyaltyTier tier = tierEngine.currentTier(m, tiers);
        LoyaltyParams params = LoyaltyParams.of(rs);
        int tierNights = TierCalculator.count(ledgerRepo.findActiveBalanceEntries(m.getId()), params, m.getTierCountFrom(), LocalDate.now(clock)).tierNights();
        LoyaltyTier next = TierCalculator.nextTier(tiers, tier).orElse(null);
        Progress progress = new Progress(
                tier == null ? null : tier.getCode(), tier == null ? null : tier.getName(),
                next == null ? null : next.getCode(), next == null ? null : next.getThreshold(),
                next == null ? null : Math.max(0, next.getThreshold() - tierNights), tierNights);
        return new MemberView(m, contact, tier, progress, identity.identifiers(m.getContactId()), identity.memberships(m.getContactId()),
                identity.consents(m.getContactId()));
    }

    public List<LoyaltyLedgerEntry> ledger(Long tenantId, Long memberId) {
        require(tenantId, memberId);
        return ledgerRepo.findByMemberIdOrderByIdDesc(memberId);
    }

    public List<LoyaltyTierHistory> tierHistory(Long tenantId, Long memberId) {
        require(tenantId, memberId);
        return historyRepo.findByMemberIdOrderByIdDesc(memberId);
    }

    /** Admin "Add member" / web sign-up: one person per e-mail, one membership per programme. */
    @Transactional
    public LoyaltyMember enrol(Long tenantId, Long programId, MemberInput in, LoyaltyMember.Source source) {
        LoyaltyProgram program = programService.require(tenantId, programId);
        String email = LoyaltyNormalizer.email(in.email());
        if (!StringUtils.hasText(in.firstName()) || !StringUtils.hasText(in.lastName()) || email == null) {
            throw new IllegalArgumentException("firstName, lastName and a valid email are required");
        }
        CrmContact.Source contactSource = source == LoyaltyMember.Source.WEB || source == LoyaltyMember.Source.BOOKING
                ? CrmContact.Source.WEB : CrmContact.Source.MANUAL;
        CrmContact contact = identity.findOrCreateGuest(tenantId, guestInput(in), contactSource);
        if (memberRepo.findByProgramIdAndContactId(programId, contact.getId()).isPresent()) {
            throw new IllegalStateException("This person is already a member");
        }
        LoyaltyMember referrer = null;
        if (StringUtils.hasText(in.referrerNumber())) {
            referrer = memberRepo.findByProgramIdAndMemberNumber(programId, in.referrerNumber().trim())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown referral membership number"));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        LoyaltyMember member = memberRepo.save(LoyaltyMember.builder()
                .tenantId(tenantId)
                .programId(program.getId())
                .contactId(contact.getId())
                .memberNumber(nextMemberNumber(program.getId()))
                .status(LoyaltyMember.Status.ACTIVE)
                .activatedAt(now)
                .tierOverride(false)
                .nightsTotal(0)
                .nightsCurrentYear(0)
                .nights24m(0)
                .referrerMemberId(referrer == null ? null : referrer.getId())
                .source(source == null ? LoyaltyMember.Source.ADMIN : source)
                .attrs(memberAttrs(in))
                .updatedAt(now)
                .build());
        registerCard(contact, program, member);
        identity.applyConsents(contact, in.consents(), "ENROLMENT");
        tierEngine.recompute(member, new LoyaltyTierEngine.Context(LoyaltyTierHistory.Reason.RECOMPUTE, null, "enrolment", true));
        events.publish(LoyaltyEvents.MEMBER_REGISTERED, member, events.data().put("source", member.getSource().name()));
        return member;
    }

    /** The membership number is an identity key (CARD) and a membership row on the person. */
    void registerCard(CrmContact contact, LoyaltyProgram program, LoyaltyMember member) {
        if (!identity.claimIdentifier(contact, CrmContactIdentifier.Type.CARD, member.getMemberNumber(), true, "LOYALTY")) {
            throw new IllegalStateException("Card number " + member.getMemberNumber() + " is already linked to another person");
        }
        identity.upsertMembership(contact, program.getCode(), member.getMemberNumber(), null, true, null, CrmContactMembership.Source.LOYALTY);
    }

    @Transactional
    public MemberView update(Long tenantId, Long memberId, MemberInput in) {
        LoyaltyMember member = require(tenantId, memberId);
        CrmContact contact = contactRepo.findById(member.getContactId()).orElseThrow();
        if (StringUtils.hasText(in.firstName())) contact.setFirstName(in.firstName().trim());
        if (StringUtils.hasText(in.lastName())) contact.setLastName(in.lastName().trim());
        if (in.phone() != null) {
            contact.setPhone(LoyaltyNormalizer.text(in.phone()));
            String phone = LoyaltyNormalizer.phone(in.phone());
            if (phone != null) identity.claimIdentifier(contact, CrmContactIdentifier.Type.PHONE, phone, true, "PROFILE");
        }
        if (in.country() != null) contact.setCountry(LoyaltyNormalizer.code(in.country()));
        if (in.city() != null) contact.setCity(LoyaltyNormalizer.text(in.city()));
        if (in.postalCode() != null) contact.setPostalCode(LoyaltyNormalizer.text(in.postalCode()));
        if (in.address() != null) contact.setAddressLine(LoyaltyNormalizer.text(in.address()));
        if (in.dateOfBirth() != null) contact.setDateOfBirth(in.dateOfBirth());
        if (in.gender() != null) contact.setGender(LoyaltyNormalizer.code(in.gender()));
        if (in.language() != null) contact.setLanguage(LoyaltyNormalizer.text(in.language()));
        String email = LoyaltyNormalizer.email(in.email());
        if (email != null && !email.equalsIgnoreCase(contact.getEmail())) {
            identity.ownerOf(tenantId, CrmContactIdentifier.Type.EMAIL, email)
                    .filter(c -> !c.getId().equals(contact.getId()))
                    .ifPresent(c -> {
                        throw new IllegalStateException("E-mail belongs to another person");
                    });
            // the old address stays as a secondary key so earlier reservations still match
            contact.setEmail(email);
            identity.claimIdentifier(contact, CrmContactIdentifier.Type.EMAIL, email, true, "PROFILE");
        }
        contactRepo.save(contact);
        if (in.interests() != null) {
            ObjectNode attrs = member.getAttrs() == null ? objectMapper.createObjectNode() : (ObjectNode) member.getAttrs();
            attrs.set("interests", objectMapper.valueToTree(in.interests().stream().filter(StringUtils::hasText).map(String::trim).toList()));
            member.setAttrs(attrs);
        }
        member.setUpdatedAt(OffsetDateTime.now(clock));
        memberRepo.save(member);
        if (in.consents() != null) {
            identity.applyConsents(contact, in.consents(), "PROFILE");
        }
        events.publish(LoyaltyEvents.MEMBER_UPDATED, member, null);
        return view(tenantId, memberId);
    }

    @Transactional
    public CrmContactIdentifier addSecondaryEmail(Long tenantId, Long memberId, String rawEmail) {
        LoyaltyMember member = require(tenantId, memberId);
        CrmContact contact = contactRepo.findById(member.getContactId()).orElseThrow();
        String email = LoyaltyNormalizer.email(rawEmail);
        if (email == null) {
            throw new IllegalArgumentException("A valid email is required");
        }
        if (!identity.claimIdentifier(contact, CrmContactIdentifier.Type.EMAIL, email, false, "ADMIN")) {
            throw new IllegalStateException("E-mail is already linked to another person");
        }
        return identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, CrmContactIdentifier.Type.EMAIL, email).orElseThrow();
    }

    @Transactional
    public void removeIdentifier(Long tenantId, Long memberId, Long identifierId) {
        LoyaltyMember member = require(tenantId, memberId);
        CrmContactIdentifier id = identifierRepo.findById(identifierId)
                .filter(i -> i.getContactId().equals(member.getContactId()))
                .orElseThrow(() -> new IllegalArgumentException("Identifier not found"));
        if (Boolean.TRUE.equals(id.getIsPrimary()) || id.getType() == CrmContactIdentifier.Type.CARD || id.getType() == CrmContactIdentifier.Type.OPERA_PROFILE) {
            throw new IllegalStateException("Primary, card and PMS identifiers cannot be removed");
        }
        identifierRepo.delete(id);
    }

    /**
     * Wallet card added (FR-06.3): irreversible, and grants the one-time wallet bonus. Without a wallet signal an
     * operator may record an exception with a reason (FR-06.6); that unlocks collecting nights but gives no bonus.
     */
    @Transactional
    public LoyaltyMember markWallet(Long tenantId, Long memberId, String exceptionReason, Long operatorUserId) {
        LoyaltyMember member = memberRepo.lockById(memberId).filter(m -> m.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Member not found"));
        if (StringUtils.hasText(exceptionReason)) {
            if (operatorUserId == null) {
                throw new IllegalArgumentException("A wallet exception needs an identified operator");
            }
            member.setWalletExceptionReason(exceptionReason.trim());
            member.setUpdatedAt(OffsetDateTime.now(clock));
            return memberRepo.save(member);
        }
        if (member.getWalletAddedAt() != null) {
            return member;
        }
        member.setWalletAddedAt(OffsetDateTime.now(clock));
        memberRepo.save(member);
        LoyaltyRuleSet rs = programService.ruleSetOn(member.getProgramId(), LocalDate.now(clock));
        int bonus = LoyaltyParams.of(rs).walletBonusNights();
        if (bonus > 0 && !ledgerRepo.existsByMemberIdAndSource(member.getId(), LoyaltyLedgerEntry.Source.WALLET)) {
            ledgerService.append(LoyaltyLedgerEntry.builder()
                    .tenantId(tenantId)
                    .memberId(member.getId())
                    .metric(LoyaltyLedgerEntry.Metric.BONUS_NIGHT)
                    .qty(bonus)
                    .asOf(LocalDate.now(clock))
                    .source(LoyaltyLedgerEntry.Source.WALLET)
                    .ruleSetId(rs.getId())
                    .operatorUserId(operatorUserId)
                    .reason("wallet card added")
                    .build());
        }
        tierEngine.recompute(member, LoyaltyTierEngine.Context.engine());
        events.publish(LoyaltyEvents.MEMBER_WALLET_ADDED, member, events.data().put("bonusNights", bonus));
        return member;
    }

    @Transactional
    public LoyaltyMember deactivate(Long tenantId, Long memberId, String reason) {
        LoyaltyMember member = require(tenantId, memberId);
        member.setStatus(LoyaltyMember.Status.DEACTIVATED);
        ObjectNode attrs = member.getAttrs() == null ? objectMapper.createObjectNode() : (ObjectNode) member.getAttrs();
        attrs.put("deactivatedReason", reason);
        attrs.put("deactivatedAt", OffsetDateTime.now(clock).toString());
        member.setAttrs(attrs);
        member.setUpdatedAt(OffsetDateTime.now(clock));
        memberRepo.save(member);
        events.publish(LoyaltyEvents.MEMBER_DEACTIVATED, member, events.data().put("reason", reason));
        return member;
    }

    /** GDPR erasure (FR-01.9): personal data goes, the member row, ledger and history stay as anonymous aggregates. */
    @Transactional
    public LoyaltyMember anonymize(Long tenantId, Long memberId) {
        LoyaltyMember member = require(tenantId, memberId);
        CrmContact contact = contactRepo.findById(member.getContactId()).orElseThrow();
        identity.anonymize(contact);
        member.setStatus(LoyaltyMember.Status.ANONYMIZED);
        member.setAttrs(objectMapper.createObjectNode().put("anonymizedAt", OffsetDateTime.now(clock).toString()));
        member.setUpdatedAt(OffsetDateTime.now(clock));
        return memberRepo.save(member);
    }

    // --- shared with importers ------------------------------------------------------------------------------

    static CrmContactIdentityService.GuestInput guestInput(MemberInput in) {
        return new CrmContactIdentityService.GuestInput(in.firstName(), in.lastName(), in.email(), in.phone(), in.country(), in.city(),
                in.postalCode(), in.address(), in.dateOfBirth(), in.gender(), in.language(), null, null);
    }

    JsonNode memberAttrs(MemberInput in) {
        ObjectNode a = objectMapper.createObjectNode();
        if (in.interests() != null) {
            a.set("interests", objectMapper.valueToTree(in.interests().stream().filter(StringUtils::hasText).map(String::trim).toList()));
        }
        return a;
    }

    /** Prefix + running number under a program row lock; skips numbers taken by imported members. */
    String nextMemberNumber(Long programId) {
        LoyaltyProgram program = programRepo.lockById(programId).orElseThrow();
        String prefix = program.getMemberNumberPrefix() == null ? "" : program.getMemberNumberPrefix();
        long next = program.getMemberNumberNext();
        String number = prefix + next;
        while (memberRepo.findByProgramIdAndMemberNumber(programId, number).isPresent()) {
            next++;
            number = prefix + next;
        }
        program.setMemberNumberNext(next + 1);
        programRepo.save(program);
        return number;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
