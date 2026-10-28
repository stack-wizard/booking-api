package com.stackwizard.booking_api.service.loyalty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.LoyaltyConsent;
import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import com.stackwizard.booking_api.model.LoyaltyMember;
import com.stackwizard.booking_api.model.LoyaltyMemberIdentifier;
import com.stackwizard.booking_api.model.LoyaltyProgram;
import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import com.stackwizard.booking_api.model.LoyaltyTier;
import com.stackwizard.booking_api.model.LoyaltyTierHistory;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.LoyaltyConsentRepository;
import com.stackwizard.booking_api.repository.LoyaltyLedgerRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberIdentifierRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.repository.LoyaltyProgramRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierHistoryRepository;
import com.stackwizard.booking_api.repository.LoyaltyTierRepository;
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

/** Member lifecycle: enrolment, profile, identifiers, consents, wallet flag, deactivation, anonymisation. */
@Service
public class LoyaltyMemberService {
    private final LoyaltyMemberRepository memberRepo;
    private final LoyaltyProgramRepository programRepo;
    private final LoyaltyMemberIdentifierRepository identifierRepo;
    private final LoyaltyConsentRepository consentRepo;
    private final LoyaltyTierRepository tierRepo;
    private final LoyaltyTierHistoryRepository historyRepo;
    private final LoyaltyLedgerRepository ledgerRepo;
    private final CrmContactRepository contactRepo;
    private final LoyaltyProgramService programService;
    private final LoyaltyLedgerService ledgerService;
    private final LoyaltyTierEngine tierEngine;
    private final LoyaltyEvents events;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoyaltyMemberService(LoyaltyMemberRepository memberRepo,
                                LoyaltyProgramRepository programRepo,
                                LoyaltyMemberIdentifierRepository identifierRepo,
                                LoyaltyConsentRepository consentRepo,
                                LoyaltyTierRepository tierRepo,
                                LoyaltyTierHistoryRepository historyRepo,
                                LoyaltyLedgerRepository ledgerRepo,
                                CrmContactRepository contactRepo,
                                LoyaltyProgramService programService,
                                LoyaltyLedgerService ledgerService,
                                LoyaltyTierEngine tierEngine,
                                LoyaltyEvents events,
                                Clock loyaltyClock) {
        this.memberRepo = memberRepo;
        this.programRepo = programRepo;
        this.identifierRepo = identifierRepo;
        this.consentRepo = consentRepo;
        this.tierRepo = tierRepo;
        this.historyRepo = historyRepo;
        this.ledgerRepo = ledgerRepo;
        this.contactRepo = contactRepo;
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

    public record MemberView(LoyaltyMember member, CrmContact contact, LoyaltyTier tier, Progress progress,
                             List<LoyaltyMemberIdentifier> identifiers, List<LoyaltyConsent> consents) {
    }

    public Page<LoyaltyMember> search(Long tenantId, Long programId, String q, String email, String number, Long tierId, Pageable pageable) {
        programService.require(tenantId, programId);
        return memberRepo.search(tenantId, programId, blankToNull(q), blankToNull(email), blankToNull(number), tierId, pageable);
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
        return new MemberView(m, contact, tier, progress, identifierRepo.findByMemberIdOrderByIdAsc(m.getId()),
                consentRepo.findByMemberIdOrderByChannelAsc(m.getId()));
    }

    public List<LoyaltyLedgerEntry> ledger(Long tenantId, Long memberId) {
        require(tenantId, memberId);
        return ledgerRepo.findByMemberIdOrderByIdDesc(memberId);
    }

    public List<LoyaltyTierHistory> tierHistory(Long tenantId, Long memberId) {
        require(tenantId, memberId);
        return historyRepo.findByMemberIdOrderByIdDesc(memberId);
    }

    /** Admin "Add member" / web sign-up: one person (crm_contact on the ORG) per e-mail, one membership per program. */
    @Transactional
    public LoyaltyMember enrol(Long tenantId, Long programId, MemberInput in, LoyaltyMember.Source source) {
        LoyaltyProgram program = programService.require(tenantId, programId);
        String email = LoyaltyNormalizer.email(in.email());
        if (!StringUtils.hasText(in.firstName()) || !StringUtils.hasText(in.lastName()) || email == null) {
            throw new IllegalArgumentException("firstName, lastName and a valid email are required");
        }
        if (identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, LoyaltyMemberIdentifier.Type.EMAIL, email).isPresent()) {
            throw new IllegalStateException("A member with this e-mail already exists");
        }
        CrmContact contact = upsertContact(tenantId, in, email);
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
                .attrs(profileAttrs(in))
                .updatedAt(now)
                .build());
        addIdentifier(member, LoyaltyMemberIdentifier.Type.EMAIL, email, true);
        addIdentifier(member, LoyaltyMemberIdentifier.Type.CARD, member.getMemberNumber(), true);
        String phone = LoyaltyNormalizer.phone(in.phone());
        if (phone != null) {
            addIdentifier(member, LoyaltyMemberIdentifier.Type.PHONE, phone, true);
        }
        applyConsents(member, in.consents(), "ENROLMENT");
        tierEngine.recompute(member, new LoyaltyTierEngine.Context(LoyaltyTierHistory.Reason.RECOMPUTE, null, "enrolment", true));
        events.publish(LoyaltyEvents.MEMBER_REGISTERED, member, events.data().put("source", member.getSource().name()));
        return member;
    }

    @Transactional
    public MemberView update(Long tenantId, Long memberId, MemberInput in) {
        LoyaltyMember member = require(tenantId, memberId);
        CrmContact contact = contactRepo.findById(member.getContactId()).orElseThrow();
        if (StringUtils.hasText(in.firstName())) {
            contact.setFirstName(in.firstName().trim());
        }
        if (StringUtils.hasText(in.lastName())) {
            contact.setLastName(in.lastName().trim());
        }
        if (in.phone() != null) {
            contact.setPhone(LoyaltyNormalizer.text(in.phone()));
        }
        String email = LoyaltyNormalizer.email(in.email());
        if (email != null && !email.equalsIgnoreCase(contact.getEmail())) {
            identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, LoyaltyMemberIdentifier.Type.EMAIL, email)
                    .filter(i -> !i.getMemberId().equals(memberId))
                    .ifPresent(i -> {
                        throw new IllegalStateException("E-mail belongs to another member");
                    });
            contact.setEmail(email);
            // the old address stays as a secondary identifier so earlier reservations still match
            identifierRepo.findByMemberIdOrderByIdAsc(memberId).stream()
                    .filter(i -> i.getType() == LoyaltyMemberIdentifier.Type.EMAIL && Boolean.TRUE.equals(i.getIsPrimary()))
                    .forEach(i -> {
                        i.setIsPrimary(false);
                        identifierRepo.save(i);
                    });
            if (identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, LoyaltyMemberIdentifier.Type.EMAIL, email).isEmpty()) {
                addIdentifier(member, LoyaltyMemberIdentifier.Type.EMAIL, email, true);
            }
        }
        contactRepo.save(contact);
        ObjectNode attrs = member.getAttrs() == null ? objectMapper.createObjectNode() : (ObjectNode) member.getAttrs();
        attrs.setAll(profileAttrs(in));
        member.setAttrs(attrs);
        member.setUpdatedAt(OffsetDateTime.now(clock));
        memberRepo.save(member);
        if (in.consents() != null) {
            applyConsents(member, in.consents(), "PROFILE");
        }
        events.publish(LoyaltyEvents.MEMBER_UPDATED, member, null);
        return view(tenantId, memberId);
    }

    @Transactional
    public LoyaltyMemberIdentifier addSecondaryEmail(Long tenantId, Long memberId, String rawEmail) {
        LoyaltyMember member = require(tenantId, memberId);
        String email = LoyaltyNormalizer.email(rawEmail);
        if (email == null) {
            throw new IllegalArgumentException("A valid email is required");
        }
        if (identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, LoyaltyMemberIdentifier.Type.EMAIL, email).isPresent()) {
            throw new IllegalStateException("E-mail is already linked to a member");
        }
        return addIdentifier(member, LoyaltyMemberIdentifier.Type.EMAIL, email, false);
    }

    @Transactional
    public void removeIdentifier(Long tenantId, Long memberId, Long identifierId) {
        require(tenantId, memberId);
        LoyaltyMemberIdentifier id = identifierRepo.findById(identifierId)
                .filter(i -> i.getMemberId().equals(memberId))
                .orElseThrow(() -> new IllegalArgumentException("Identifier not found"));
        if (Boolean.TRUE.equals(id.getIsPrimary())) {
            throw new IllegalStateException("The primary identifier cannot be removed");
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
        ObjectNode attrs = (ObjectNode) member.getAttrs();
        attrs.put("deactivatedReason", reason);
        attrs.put("deactivatedAt", OffsetDateTime.now(clock).toString());
        member.setUpdatedAt(OffsetDateTime.now(clock));
        memberRepo.save(member);
        events.publish(LoyaltyEvents.MEMBER_DEACTIVATED, member, events.data().put("reason", reason));
        return member;
    }

    /**
     * GDPR erasure (FR-01.9): personal data on the contact, identifiers and profile go; the member row, ledger and
     * history stay as anonymous aggregates. No event: there is nobody left to contact.
     */
    @Transactional
    public LoyaltyMember anonymize(Long tenantId, Long memberId) {
        LoyaltyMember member = require(tenantId, memberId);
        CrmContact contact = contactRepo.findById(member.getContactId()).orElseThrow();
        contact.setFirstName("Anonymized");
        contact.setLastName("Member " + member.getId());
        contact.setEmail(null);
        contact.setPhone(null);
        contact.setPlatformUserId(null);
        contact.setActive(false);
        contact.setAttrs(objectMapper.createObjectNode());
        contactRepo.save(contact);
        identifierRepo.findByMemberIdOrderByIdAsc(memberId).stream()
                .filter(i -> i.getType() != LoyaltyMemberIdentifier.Type.CARD)
                .forEach(identifierRepo::delete);
        member.setStatus(LoyaltyMember.Status.ANONYMIZED);
        member.setAttrs(objectMapper.createObjectNode().put("anonymizedAt", OffsetDateTime.now(clock).toString()));
        member.setUpdatedAt(OffsetDateTime.now(clock));
        return memberRepo.save(member);
    }

    // --- shared with importers ------------------------------------------------------------------------------

    CrmContact upsertContact(Long tenantId, MemberInput in, String email) {
        CrmContact contact = contactRepo.findFirstByTenantIdAndEmailIgnoreCaseOrderByIdAsc(tenantId, email).orElse(null);
        if (contact == null) {
            contact = CrmContact.builder()
                    .tenantId(tenantId)
                    .firstName(in.firstName().trim())
                    .lastName(in.lastName().trim())
                    .email(email)
                    .phone(LoyaltyNormalizer.text(in.phone()))
                    .active(true)
                    .attrs(objectMapper.createObjectNode())
                    .build();
        } else if (contact.getPhone() == null && in.phone() != null) {
            contact.setPhone(LoyaltyNormalizer.text(in.phone()));
        }
        return contactRepo.save(contact);
    }

    LoyaltyMemberIdentifier addIdentifier(LoyaltyMember member, LoyaltyMemberIdentifier.Type type, String value, boolean primary) {
        return identifierRepo.save(LoyaltyMemberIdentifier.builder()
                .tenantId(member.getTenantId())
                .memberId(member.getId())
                .type(type)
                .valueNorm(value)
                .isPrimary(primary)
                .build());
    }

    void applyConsents(LoyaltyMember member, Map<String, Boolean> consents, String source) {
        if (consents == null) {
            return;
        }
        for (Map.Entry<String, Boolean> e : consents.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            LoyaltyConsent.Channel channel;
            try {
                channel = LoyaltyConsent.Channel.valueOf(e.getKey().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown consent channel " + e.getKey());
            }
            LoyaltyConsent c = consentRepo.findByMemberIdAndChannel(member.getId(), channel)
                    .orElse(LoyaltyConsent.builder().tenantId(member.getTenantId()).memberId(member.getId()).channel(channel).build());
            if (c.getId() != null && e.getValue().equals(c.getGranted())) {
                continue;
            }
            c.setGranted(e.getValue());
            c.setChangedAt(OffsetDateTime.now(clock));
            c.setSource(source);
            consentRepo.save(c);
        }
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

    ObjectNode profileAttrs(MemberInput in) {
        ObjectNode a = objectMapper.createObjectNode();
        putIfText(a, "country", in.country() == null ? null : in.country().trim().toUpperCase());
        putIfText(a, "city", in.city());
        putIfText(a, "postalCode", in.postalCode());
        putIfText(a, "address", in.address());
        putIfText(a, "gender", in.gender());
        putIfText(a, "language", in.language());
        if (in.dateOfBirth() != null) {
            a.put("dateOfBirth", in.dateOfBirth().toString());
        }
        if (in.interests() != null) {
            JsonNode arr = objectMapper.valueToTree(in.interests().stream().filter(StringUtils::hasText).map(String::trim).toList());
            a.set("interests", arr);
        }
        return a;
    }

    private static void putIfText(ObjectNode node, String key, String value) {
        if (StringUtils.hasText(value)) {
            node.put(key, value.trim());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
