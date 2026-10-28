package com.stackwizard.booking_api.service.crm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.CrmContactConsent;
import com.stackwizard.booking_api.model.CrmContactIdentifier;
import com.stackwizard.booking_api.model.CrmContactMembership;
import com.stackwizard.booking_api.model.CrmContactMergeLog;
import com.stackwizard.booking_api.repository.CrmContactConsentRepository;
import com.stackwizard.booking_api.repository.CrmContactIdentifierRepository;
import com.stackwizard.booking_api.repository.CrmContactMembershipRepository;
import com.stackwizard.booking_api.repository.CrmContactMergeLogRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.LoyaltyMemberRepository;
import com.stackwizard.booking_api.service.loyalty.LoyaltyNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One person, many keys: how a PMS profile, a loyalty sign-up or an admin form becomes (or finds) a {@code crm_contact},
 * which identifiers it may claim, and how two profiles are merged. The PMS is the source of truth for OPERA-sourced
 * contacts; manually created contacts only get blanks filled.
 */
@Service
public class CrmContactIdentityService {
    private final CrmContactRepository contactRepo;
    private final CrmContactIdentifierRepository identifierRepo;
    private final CrmContactMembershipRepository membershipRepo;
    private final CrmContactConsentRepository consentRepo;
    private final CrmContactMergeLogRepository mergeLogRepo;
    private final LoyaltyMemberRepository memberRepo;
    private final CrmGuestStatsService statsService;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrmContactIdentityService(CrmContactRepository contactRepo,
                                     CrmContactIdentifierRepository identifierRepo,
                                     CrmContactMembershipRepository membershipRepo,
                                     CrmContactConsentRepository consentRepo,
                                     CrmContactMergeLogRepository mergeLogRepo,
                                     LoyaltyMemberRepository memberRepo,
                                     CrmGuestStatsService statsService,
                                     JdbcTemplate jdbc,
                                     Clock loyaltyClock) {
        this.contactRepo = contactRepo;
        this.identifierRepo = identifierRepo;
        this.membershipRepo = membershipRepo;
        this.consentRepo = consentRepo;
        this.mergeLogRepo = mergeLogRepo;
        this.memberRepo = memberRepo;
        this.statsService = statsService;
        this.jdbc = jdbc;
        this.clock = loyaltyClock;
    }

    public record GuestInput(String firstName, String lastName, String email, String phone, String country, String city,
                             String postalCode, String addressLine, LocalDate dateOfBirth, String gender, String language,
                             String nationality, String vipCode) {
    }

    public record PmsMembership(String programCode, String cardNo, String level, boolean primary, LocalDate expiresAt) {
    }

    public record PmsProfile(String pmsProfileId, GuestInput guest, List<PmsMembership> memberships, Map<String, Boolean> consents,
                             Integer openingStays, Integer openingNights, BigDecimal openingRevenue, LocalDate openingAsOf,
                             JsonNode stats, boolean inactive, String mergedIntoPmsProfileId) {
    }

    /** A person for a sign-up or an admin form: by e-mail, else new GUEST. Updates blanks only on an existing contact. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CrmContact findOrCreateGuest(Long tenantId, GuestInput in, CrmContact.Source source) {
        String email = LoyaltyNormalizer.email(in.email());
        CrmContact contact = email == null ? null : findByEmail(tenantId, email).orElse(null);
        if (contact == null) {
            contact = newContact(tenantId, source);
            contact.setFirstName(trim(in.firstName()));
            contact.setLastName(trim(in.lastName()));
            contact.setEmail(email);
        }
        applyGuest(contact, in, false);
        contact = contactRepo.save(contact);
        if (email != null) {
            claimIdentifier(contact, CrmContactIdentifier.Type.EMAIL, email, true, source.name());
        }
        String phone = LoyaltyNormalizer.phone(in.phone());
        if (phone != null) {
            claimIdentifier(contact, CrmContactIdentifier.Type.PHONE, phone, true, source.name());
        }
        return contact;
    }

    /** A PMS profile: by PMS id, else by e-mail, else new. The PMS overwrites what it owns. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CrmContact upsertFromPms(Long tenantId, PmsProfile p) {
        String profileId = LoyaltyNormalizer.text(p.pmsProfileId());
        if (profileId == null) {
            throw new IllegalArgumentException("pmsProfileId is required");
        }
        GuestInput in = p.guest();
        String email = LoyaltyNormalizer.email(in.email());
        CrmContact contact = contactRepo.findByTenantIdAndOperaProfileId(tenantId, profileId)
                .or(() -> identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, CrmContactIdentifier.Type.OPERA_PROFILE, profileId)
                        .flatMap(i -> contactRepo.findById(i.getContactId())))
                .or(() -> email == null ? Optional.empty() : findByEmail(tenantId, email).filter(c -> c.getOperaProfileId() == null))
                .orElse(null);
        boolean created = contact == null;
        if (created) {
            contact = newContact(tenantId, CrmContact.Source.OPERA);
            contact.setFirstName(trim(in.firstName()));
            contact.setLastName(trim(in.lastName()));
        }
        contact.setOperaProfileId(profileId);
        boolean pmsOwned = contact.getSource() == CrmContact.Source.OPERA || contact.getSource() == CrmContact.Source.IMPORT;
        applyGuest(contact, in, pmsOwned);
        if (pmsOwned && email != null && !email.equalsIgnoreCase(contact.getEmail())) {
            contact.setEmail(email);
        }
        if (p.openingAsOf() != null) {
            contact.setPmsOpeningStays(p.openingStays() == null ? 0 : p.openingStays());
            contact.setPmsOpeningNights(p.openingNights() == null ? 0 : p.openingNights());
            contact.setPmsOpeningRevenue(p.openingRevenue() == null ? BigDecimal.ZERO : p.openingRevenue());
            contact.setPmsOpeningAsOf(p.openingAsOf());
        }
        if (p.stats() != null) {
            contact.setPmsStats(p.stats());
        }
        if (p.inactive()) {
            contact.setActive(false);
        }
        contact = contactRepo.save(contact);
        claimIdentifier(contact, CrmContactIdentifier.Type.OPERA_PROFILE, profileId, true, "OPERA");
        if (email != null) {
            claimIdentifier(contact, CrmContactIdentifier.Type.EMAIL, email, email.equalsIgnoreCase(contact.getEmail()), "OPERA");
        }
        String phone = LoyaltyNormalizer.phone(in.phone());
        if (phone != null) {
            claimIdentifier(contact, CrmContactIdentifier.Type.PHONE, phone, false, "OPERA");
        }
        if (p.memberships() != null) {
            for (PmsMembership m : p.memberships()) {
                upsertMembership(contact, m.programCode(), m.cardNo(), m.level(), m.primary(), m.expiresAt(), CrmContactMembership.Source.OPERA);
            }
        }
        applyConsents(contact, p.consents(), "OPERA");
        if (p.mergedIntoPmsProfileId() != null) {
            CrmContact self = contact;
            contactRepo.findByTenantIdAndOperaProfileId(tenantId, p.mergedIntoPmsProfileId())
                    .filter(into -> !into.getId().equals(self.getId()))
                    .ifPresent(into -> merge(tenantId, self.getId(), into.getId(), "PMS profile merge", null));
        }
        statsService.recomputeContact(contact.getId());
        return contact;
    }

    /**
     * Gives the contact an identifier. A key already owned by someone else is left alone (returns false) rather than
     * stolen: that conflict is a duplicate to merge, visible in the import report.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimIdentifier(CrmContact contact, CrmContactIdentifier.Type type, String value, boolean primary, String source) {
        Optional<CrmContactIdentifier> existing = identifierRepo.findByTenantIdAndTypeAndValueNorm(contact.getTenantId(), type, value);
        if (existing.isPresent()) {
            CrmContactIdentifier id = existing.get();
            if (!id.getContactId().equals(contact.getId())) {
                return false;
            }
            if (primary && !Boolean.TRUE.equals(id.getIsPrimary())) {
                demotePrimary(contact.getId(), type);
                id.setIsPrimary(true);
                identifierRepo.save(id);
            }
            return true;
        }
        if (primary) {
            demotePrimary(contact.getId(), type);
        }
        identifierRepo.save(CrmContactIdentifier.builder().tenantId(contact.getTenantId()).contactId(contact.getId())
                .type(type).valueNorm(value).isPrimary(primary).source(source).build());
        return true;
    }

    public Optional<CrmContact> ownerOf(Long tenantId, CrmContactIdentifier.Type type, String value) {
        return identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, type, value).flatMap(i -> contactRepo.findById(i.getContactId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CrmContactMembership upsertMembership(CrmContact contact, String programCode, String cardNo, String level, boolean primary,
                                                 LocalDate expiresAt, CrmContactMembership.Source source) {
        String code = LoyaltyNormalizer.code(programCode);
        String card = LoyaltyNormalizer.text(cardNo);
        if (code == null || card == null) {
            throw new IllegalArgumentException("programCode and cardNo are required");
        }
        CrmContactMembership m = membershipRepo.findByTenantIdAndProgramCodeIgnoreCaseAndCardNo(contact.getTenantId(), code, card)
                .orElse(CrmContactMembership.builder().tenantId(contact.getTenantId()).contactId(contact.getId()).programCode(code).cardNo(card)
                        .isPrimary(false).source(source).build());
        if (!m.getContactId().equals(contact.getId())) {
            throw new IllegalStateException("Card " + card + " of " + code + " belongs to another person");
        }
        if (level != null) m.setLevel(level);
        if (expiresAt != null) m.setExpiresAt(expiresAt);
        m.setIsPrimary(primary || Boolean.TRUE.equals(m.getIsPrimary()));
        m.setUpdatedAt(OffsetDateTime.now(clock));
        return membershipRepo.save(m);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyConsents(CrmContact contact, Map<String, Boolean> consents, String source) {
        if (consents == null) {
            return;
        }
        for (Map.Entry<String, Boolean> e : consents.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            CrmContactConsent.Channel channel;
            try {
                channel = CrmContactConsent.Channel.valueOf(e.getKey().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unknown consent channel " + e.getKey());
            }
            CrmContactConsent c = consentRepo.findByContactIdAndChannel(contact.getId(), channel)
                    .orElse(CrmContactConsent.builder().tenantId(contact.getTenantId()).contactId(contact.getId()).channel(channel).build());
            if (c.getId() != null && e.getValue().equals(c.getGranted())) {
                continue;
            }
            c.setGranted(e.getValue());
            c.setChangedAt(OffsetDateTime.now(clock));
            c.setSource(source);
            consentRepo.save(c);
        }
    }

    /**
     * Folds {@code from} into {@code into}: keys, cards, consents, reservations and (if only one side has it) the
     * loyalty membership move; {@code from} stays as an inactive pointer so old links still resolve.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CrmContact merge(Long tenantId, Long fromId, Long intoId, String reason, Long operatorUserId) {
        if (Objects.equals(fromId, intoId)) {
            throw new IllegalArgumentException("Cannot merge a contact into itself");
        }
        CrmContact from = contactRepo.findByIdAndTenantId(fromId, tenantId).orElseThrow(() -> new IllegalArgumentException("Contact not found"));
        CrmContact into = contactRepo.findByIdAndTenantId(intoId, tenantId).orElseThrow(() -> new IllegalArgumentException("Contact not found"));
        if (into.getMergedIntoId() != null) {
            throw new IllegalStateException("Target contact was itself merged");
        }
        boolean fromIsMember = !memberRepo.findByContactIdOrderByIdAsc(fromId).isEmpty();
        boolean intoIsMember = !memberRepo.findByContactIdOrderByIdAsc(intoId).isEmpty();
        if (fromIsMember && intoIsMember) {
            throw new IllegalStateException("Both contacts hold loyalty memberships; merge the memberships first");
        }
        for (CrmContactIdentifier id : identifierRepo.findByContactIdOrderByIdAsc(fromId)) {
            id.setContactId(intoId);
            id.setIsPrimary(false);
            identifierRepo.save(id);
        }
        for (CrmContactMembership m : membershipRepo.findByContactIdOrderByIdAsc(fromId)) {
            m.setContactId(intoId);
            membershipRepo.save(m);
        }
        for (CrmContactConsent c : consentRepo.findByContactIdOrderByChannelAsc(fromId)) {
            if (consentRepo.findByContactIdAndChannel(intoId, c.getChannel()).isEmpty()) {
                c.setContactId(intoId);
                consentRepo.save(c);
            }
        }
        jdbc.update("update pms_reservation set contact_id = ? where contact_id = ?", intoId, fromId);
        jdbc.update("update pms_reservation_guest set contact_id = ? where contact_id = ?", intoId, fromId);
        jdbc.update("update loyalty_member set contact_id = ? where contact_id = ?", intoId, fromId);
        if (into.getOperaProfileId() == null && from.getOperaProfileId() != null) {
            String pid = from.getOperaProfileId();
            from.setOperaProfileId(null);
            contactRepo.saveAndFlush(from);
            into.setOperaProfileId(pid);
        }
        fillBlanks(into, from);
        into.setPmsOpeningStays(nz(into.getPmsOpeningStays()) + nz(from.getPmsOpeningStays()));
        into.setPmsOpeningNights(nz(into.getPmsOpeningNights()) + nz(from.getPmsOpeningNights()));
        into.setPmsOpeningRevenue(nz(into.getPmsOpeningRevenue()).add(nz(from.getPmsOpeningRevenue())));
        if (into.getPmsOpeningAsOf() == null || (from.getPmsOpeningAsOf() != null && from.getPmsOpeningAsOf().isAfter(into.getPmsOpeningAsOf()))) {
            into.setPmsOpeningAsOf(from.getPmsOpeningAsOf());
        }
        contactRepo.save(into);
        from.setMergedIntoId(intoId);
        from.setActive(false);
        contactRepo.save(from);
        mergeLogRepo.save(CrmContactMergeLog.builder().tenantId(tenantId).fromContactId(fromId).intoContactId(intoId)
                .reason(reason).operatorUserId(operatorUserId).build());
        statsService.recomputeContact(intoId);
        return into;
    }

    /** GDPR erasure: personal data goes, the row and aggregates stay. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void anonymize(CrmContact contact) {
        contact.setFirstName("Anonymized");
        contact.setLastName("Contact " + contact.getId());
        contact.setEmail(null);
        contact.setPhone(null);
        contact.setPlatformUserId(null);
        contact.setDateOfBirth(null);
        contact.setAddressLine(null);
        contact.setPostalCode(null);
        contact.setCity(null);
        contact.setGender(null);
        contact.setVipCode(null);
        contact.setActive(false);
        contact.setAttrs(objectMapper.createObjectNode());
        contactRepo.save(contact);
        identifierRepo.findByContactIdOrderByIdAsc(contact.getId()).stream()
                .filter(i -> i.getType() != CrmContactIdentifier.Type.CARD && i.getType() != CrmContactIdentifier.Type.OPERA_PROFILE)
                .forEach(identifierRepo::delete);
        for (CrmContactConsent c : consentRepo.findByContactIdOrderByChannelAsc(contact.getId())) {
            if (Boolean.TRUE.equals(c.getGranted())) {
                c.setGranted(false);
                c.setChangedAt(OffsetDateTime.now(clock));
                c.setSource("ANONYMIZED");
                consentRepo.save(c);
            }
        }
    }

    public List<CrmContactIdentifier> identifiers(Long contactId) {
        return identifierRepo.findByContactIdOrderByIdAsc(contactId);
    }

    public List<CrmContactMembership> memberships(Long contactId) {
        return membershipRepo.findByContactIdOrderByIdAsc(contactId);
    }

    public List<CrmContactConsent> consents(Long contactId) {
        return consentRepo.findByContactIdOrderByChannelAsc(contactId);
    }

    public Optional<CrmContact> findByEmail(Long tenantId, String emailNorm) {
        return identifierRepo.findByTenantIdAndTypeAndValueNorm(tenantId, CrmContactIdentifier.Type.EMAIL, emailNorm)
                .flatMap(i -> contactRepo.findById(i.getContactId()))
                .or(() -> contactRepo.findFirstByTenantIdAndEmailIgnoreCaseOrderByIdAsc(tenantId, emailNorm))
                .map(this::resolveMerged);
    }

    public CrmContact resolveMerged(CrmContact c) {
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

    // --- helpers --------------------------------------------------------------------------------------------

    private CrmContact newContact(Long tenantId, CrmContact.Source source) {
        return CrmContact.builder()
                .tenantId(tenantId)
                .kind(CrmContact.Kind.GUEST)
                .source(source)
                .active(true)
                .attrs(objectMapper.createObjectNode())
                .pmsStats(objectMapper.createObjectNode())
                .pmsOpeningStays(0).pmsOpeningNights(0).pmsOpeningRevenue(BigDecimal.ZERO)
                .staysTotal(0).nightsTotal(0).revenueTotal(BigDecimal.ZERO).roomRevenueTotal(BigDecimal.ZERO)
                .build();
    }

    /** {@code overwrite} for PMS-owned contacts; otherwise only blanks are filled. */
    private static void applyGuest(CrmContact c, GuestInput in, boolean overwrite) {
        if (in == null) {
            return;
        }
        if (set(overwrite, c.getFirstName(), in.firstName())) c.setFirstName(trim(in.firstName()));
        if (set(overwrite, c.getLastName(), in.lastName())) c.setLastName(trim(in.lastName()));
        if (set(overwrite, c.getPhone(), in.phone())) c.setPhone(trim(in.phone()));
        if (set(overwrite, c.getCountry(), in.country())) c.setCountry(upper(in.country()));
        if (set(overwrite, c.getNationality(), in.nationality())) c.setNationality(upper(in.nationality()));
        if (set(overwrite, c.getCity(), in.city())) c.setCity(trim(in.city()));
        if (set(overwrite, c.getPostalCode(), in.postalCode())) c.setPostalCode(trim(in.postalCode()));
        if (set(overwrite, c.getAddressLine(), in.addressLine())) c.setAddressLine(trim(in.addressLine()));
        if (set(overwrite, c.getGender(), in.gender())) c.setGender(upper(in.gender()));
        if (set(overwrite, c.getLanguage(), in.language())) c.setLanguage(trim(in.language()).toLowerCase());
        if (set(overwrite, c.getVipCode(), in.vipCode())) c.setVipCode(trim(in.vipCode()));
        if (in.dateOfBirth() != null && (overwrite || c.getDateOfBirth() == null)) c.setDateOfBirth(in.dateOfBirth());
        if (c.getEmail() == null && LoyaltyNormalizer.email(in.email()) != null) c.setEmail(LoyaltyNormalizer.email(in.email()));
    }

    private static void fillBlanks(CrmContact into, CrmContact from) {
        if (!StringUtils.hasText(into.getEmail())) into.setEmail(from.getEmail());
        if (!StringUtils.hasText(into.getPhone())) into.setPhone(from.getPhone());
        if (into.getCountry() == null) into.setCountry(from.getCountry());
        if (into.getNationality() == null) into.setNationality(from.getNationality());
        if (into.getCity() == null) into.setCity(from.getCity());
        if (into.getPostalCode() == null) into.setPostalCode(from.getPostalCode());
        if (into.getAddressLine() == null) into.setAddressLine(from.getAddressLine());
        if (into.getDateOfBirth() == null) into.setDateOfBirth(from.getDateOfBirth());
        if (into.getLanguage() == null) into.setLanguage(from.getLanguage());
        if (into.getPlatformUserId() == null) into.setPlatformUserId(from.getPlatformUserId());
    }

    private void demotePrimary(Long contactId, CrmContactIdentifier.Type type) {
        for (CrmContactIdentifier i : identifierRepo.findByContactIdOrderByIdAsc(contactId)) {
            if (i.getType() == type && Boolean.TRUE.equals(i.getIsPrimary())) {
                i.setIsPrimary(false);
                identifierRepo.save(i);
            }
        }
    }

    private static boolean set(boolean overwrite, String current, String incoming) {
        return StringUtils.hasText(incoming) && (overwrite || !StringUtils.hasText(current));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @SuppressWarnings("unused")
    private ObjectNode node() {
        return objectMapper.createObjectNode();
    }
}
