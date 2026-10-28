package com.stackwizard.booking_api.service.pms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.LoyaltyReservationEvaluation;
import com.stackwizard.booking_api.model.PmsReservation;
import com.stackwizard.booking_api.model.PmsReservationGuest;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.LoyaltyReservationEvaluationRepository;
import com.stackwizard.booking_api.repository.PmsReservationGuestRepository;
import com.stackwizard.booking_api.repository.PmsReservationRepository;
import com.stackwizard.booking_api.service.crm.CrmGuestStatsService;
import com.stackwizard.booking_api.service.integration.IntegrationEventPublisher;
import com.stackwizard.booking_api.service.loyalty.LoyaltyMatchingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The platform's copy of PMS reservations. {@link #upsert} is the single write path for every source (CSV export,
 * connector, our own bookings): it matches the person, links accounts, emits {@code pms.reservation.*} events,
 * queues the loyalty evaluation and refreshes CRM statistics. Consumers never write here.
 */
@Service
public class PmsReservationService {
    public static final String EVENT_CREATED = "pms.reservation.created";
    public static final String EVENT_CHANGED = "pms.reservation.changed";
    public static final String EVENT_CHECKED_IN = "pms.reservation.checked_in";
    public static final String EVENT_CHECKED_OUT = "pms.reservation.checked_out";
    public static final String EVENT_CANCELLED = "pms.reservation.cancelled";
    public static final String EVENT_NO_SHOW = "pms.reservation.no_show";

    private final PmsReservationRepository reservationRepo;
    private final PmsReservationGuestRepository guestRepo;
    private final LoyaltyReservationEvaluationRepository evaluationRepo;
    private final CrmContactRepository contactRepo;
    private final CrmAccountRepository accountRepo;
    private final LoyaltyMatchingService matchingService;
    private final CrmGuestStatsService statsService;
    private final IntegrationEventPublisher publisher;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PmsReservationService(PmsReservationRepository reservationRepo,
                                 PmsReservationGuestRepository guestRepo,
                                 LoyaltyReservationEvaluationRepository evaluationRepo,
                                 CrmContactRepository contactRepo,
                                 CrmAccountRepository accountRepo,
                                 LoyaltyMatchingService matchingService,
                                 CrmGuestStatsService statsService,
                                 IntegrationEventPublisher publisher,
                                 Clock loyaltyClock) {
        this.reservationRepo = reservationRepo;
        this.guestRepo = guestRepo;
        this.evaluationRepo = evaluationRepo;
        this.contactRepo = contactRepo;
        this.accountRepo = accountRepo;
        this.matchingService = matchingService;
        this.statsService = statsService;
        this.publisher = publisher;
        this.clock = loyaltyClock;
    }

    public record ReservationView(PmsReservation reservation, List<PmsReservationGuest> guests,
                                  List<LoyaltyReservationEvaluation> evaluations) {
    }

    public Page<PmsReservation> search(Long tenantId, PmsReservation.Status status, Long propertyTenantId, Long contactId,
                                       boolean unmatched, String q, Pageable pageable) {
        return reservationRepo.search(tenantId, status, propertyTenantId, contactId, unmatched,
                q == null || q.isBlank() ? null : q.trim(), pageable);
    }

    public ReservationView view(Long tenantId, Long reservationId) {
        PmsReservation r = require(tenantId, reservationId);
        return new ReservationView(r, guestRepo.findByReservationIdOrderByIdAsc(r.getId()), evaluationRepo.findByReservationIdOrderByIdAsc(r.getId()));
    }

    public List<PmsReservation> forContact(Long tenantId, Long contactId) {
        contactRepo.findByIdAndTenantId(contactId, tenantId).orElseThrow(() -> new IllegalArgumentException("Contact not found"));
        return reservationRepo.findByContactIdOrderByArrivalDesc(contactId);
    }

    /**
     * Insert or update from a source snapshot. Returns the stored row. Only fields the source knows are copied: a
     * source passing {@code null} for revenue leaves an earlier value alone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PmsReservation upsert(PmsReservation incoming, Long batchId) {
        PmsReservation existing = reservationRepo.findByPropertyTenantIdAndPmsSystemAndPmsReservationId(
                incoming.getPropertyTenantId(), incoming.getPmsSystem(), incoming.getPmsReservationId()).orElse(null);
        boolean created = existing == null;
        PmsReservation r = created ? newRow(incoming, batchId) : existing;
        String qualificationBefore = created ? null : qualificationFingerprint(r);
        String allBefore = created ? null : fullFingerprint(r);
        PmsReservation.Status statusBefore = created ? null : r.getStatus();
        Long contactBefore = created ? null : r.getContactId();

        copy(incoming, r);
        if (r.getMatchMethod() != PmsReservation.MatchMethod.MANUAL) {
            matchingService.matchContact(r).ifPresentOrElse(m -> {
                r.setContactId(m.contact().getId());
                r.setMatchMethod(m.method());
            }, () -> {
                r.setContactId(null);
                r.setMatchMethod(null);
            });
        }
        r.setLastSeenBatchId(batchId);
        r.setUpdatedAt(OffsetDateTime.now(clock));
        PmsReservation saved = reservationRepo.save(r);
        linkGuests(saved);

        boolean changed = created || !Objects.equals(allBefore, fullFingerprint(saved));
        if (changed) {
            publish(saved, created ? EVENT_CREATED : eventFor(statusBefore, saved.getStatus()));
            if (created || !Objects.equals(qualificationBefore, qualificationFingerprint(saved))) {
                enqueueEvaluation(saved);
            }
            refreshStats(saved, contactBefore);
        }
        return saved;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueEvaluation(PmsReservation r) {
        if (evaluationRepo.existsByReservationIdAndResult(r.getId(), LoyaltyReservationEvaluation.Result.PENDING)) {
            return;
        }
        evaluationRepo.save(LoyaltyReservationEvaluation.builder()
                .tenantId(r.getTenantId())
                .reservationId(r.getId())
                .result(LoyaltyReservationEvaluation.Result.PENDING)
                .nightsPosted(0)
                .attempts(0)
                .details(objectMapper.createObjectNode())
                .build());
    }

    /** Back office links a reservation to a person by hand (match queue, FR-04.3). */
    @Transactional
    public ReservationView matchManually(Long tenantId, Long reservationId, Long contactId) {
        PmsReservation r = require(tenantId, reservationId);
        CrmContact contact = contactRepo.findByIdAndTenantId(contactId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));
        Long before = r.getContactId();
        r.setContactId(contact.getId());
        r.setMatchMethod(PmsReservation.MatchMethod.MANUAL);
        r.setUpdatedAt(OffsetDateTime.now(clock));
        reservationRepo.save(r);
        linkGuests(r);
        enqueueEvaluation(r);
        refreshStats(r, before);
        return view(tenantId, reservationId);
    }

    @Transactional
    public ReservationView reevaluate(Long tenantId, Long reservationId) {
        PmsReservation r = require(tenantId, reservationId);
        enqueueEvaluation(r);
        return view(tenantId, reservationId);
    }

    // --- internals ------------------------------------------------------------------------------------------

    private PmsReservation newRow(PmsReservation in, Long batchId) {
        return PmsReservation.builder()
                .tenantId(in.getTenantId())
                .propertyTenantId(in.getPropertyTenantId())
                .pmsSystem(in.getPmsSystem())
                .pmsReservationId(in.getPmsReservationId())
                .firstSeenBatchId(batchId)
                .isDayUse(false).isComplimentary(false).isHouseUse(false).isPseudoRoom(false).isWalkIn(false)
                .revenueFinal(false)
                .units(1)
                .raw(objectMapper.createObjectNode())
                .build();
    }

    /** Fields the source may know; nulls leave the stored value (sources differ in what they carry). */
    static void copy(PmsReservation in, PmsReservation r) {
        if (in.getConfirmationNo() != null) r.setConfirmationNo(in.getConfirmationNo());
        if (in.getConfirmationLegNo() != null) r.setConfirmationLegNo(in.getConfirmationLegNo());
        if (in.getParentPmsReservationId() != null) r.setParentPmsReservationId(in.getParentPmsReservationId());
        if (in.getExternalReference() != null) r.setExternalReference(in.getExternalReference());
        r.setStatus(in.getStatus());
        if (in.getStatusRaw() != null) r.setStatusRaw(in.getStatusRaw());
        r.setArrival(in.getArrival());
        r.setDeparture(in.getDeparture());
        if (in.getOriginalDeparture() != null) r.setOriginalDeparture(in.getOriginalDeparture());
        if (in.getActualCheckIn() != null) r.setActualCheckIn(in.getActualCheckIn());
        if (in.getActualCheckOut() != null) r.setActualCheckOut(in.getActualCheckOut());
        r.setNights(in.getNights());
        if (in.getUnits() != null) r.setUnits(in.getUnits());
        if (in.getAdults() != null) r.setAdults(in.getAdults());
        if (in.getChildren() != null) r.setChildren(in.getChildren());
        if (in.getBookingDate() != null) r.setBookingDate(in.getBookingDate());
        if (in.getCancelledAt() != null) r.setCancelledAt(in.getCancelledAt());
        if (in.getCancellationReason() != null) r.setCancellationReason(in.getCancellationReason());
        if (in.getRoomNo() != null) r.setRoomNo(in.getRoomNo());
        if (in.getRoomType() != null) r.setRoomType(in.getRoomType());
        if (in.getRoomTypeCharged() != null) r.setRoomTypeCharged(in.getRoomTypeCharged());
        if (in.getRateCode() != null) r.setRateCode(in.getRateCode());
        if (in.getSourceCode() != null) r.setSourceCode(in.getSourceCode());
        if (in.getMarketCode() != null) r.setMarketCode(in.getMarketCode());
        if (in.getOriginCode() != null) r.setOriginCode(in.getOriginCode());
        if (in.getChannelCode() != null) r.setChannelCode(in.getChannelCode());
        if (in.getBlockCode() != null) r.setBlockCode(in.getBlockCode());
        if (in.getGuaranteeCode() != null) r.setGuaranteeCode(in.getGuaranteeCode());
        if (in.getPaymentMethod() != null) r.setPaymentMethod(in.getPaymentMethod());
        if (in.getIsDayUse() != null) r.setIsDayUse(in.getIsDayUse());
        if (in.getIsComplimentary() != null) r.setIsComplimentary(in.getIsComplimentary());
        if (in.getIsHouseUse() != null) r.setIsHouseUse(in.getIsHouseUse());
        if (in.getIsPseudoRoom() != null) r.setIsPseudoRoom(in.getIsPseudoRoom());
        if (in.getIsWalkIn() != null) r.setIsWalkIn(in.getIsWalkIn());
        if (in.getHolderFirstName() != null) r.setHolderFirstName(in.getHolderFirstName());
        if (in.getHolderLastName() != null) r.setHolderLastName(in.getHolderLastName());
        if (in.getHolderEmailNorm() != null) r.setHolderEmailNorm(in.getHolderEmailNorm());
        if (in.getHolderPhoneNorm() != null) r.setHolderPhoneNorm(in.getHolderPhoneNorm());
        if (in.getHolderNationality() != null) r.setHolderNationality(in.getHolderNationality());
        if (in.getHolderPmsProfileId() != null) r.setHolderPmsProfileId(in.getHolderPmsProfileId());
        if (in.getMembershipType() != null) r.setMembershipType(in.getMembershipType());
        if (in.getMembershipNo() != null) r.setMembershipNo(in.getMembershipNo());
        if (in.getMembershipLevel() != null) r.setMembershipLevel(in.getMembershipLevel());
        if (in.getCompanyPmsProfileId() != null) r.setCompanyPmsProfileId(in.getCompanyPmsProfileId());
        if (in.getAgentPmsProfileId() != null) r.setAgentPmsProfileId(in.getAgentPmsProfileId());
        if (in.getGroupPmsProfileId() != null) r.setGroupPmsProfileId(in.getGroupPmsProfileId());
        if (in.getRateAmount() != null) r.setRateAmount(in.getRateAmount());
        if (in.getRoomRevenue() != null) r.setRoomRevenue(in.getRoomRevenue());
        if (in.getFbRevenue() != null) r.setFbRevenue(in.getFbRevenue());
        if (in.getOtherRevenue() != null) r.setOtherRevenue(in.getOtherRevenue());
        if (in.getTaxTotal() != null) r.setTaxTotal(in.getTaxTotal());
        if (in.getTotalRevenue() != null) r.setTotalRevenue(in.getTotalRevenue());
        if (in.getRoomRevenueAsPayee() != null) r.setRoomRevenueAsPayee(in.getRoomRevenueAsPayee());
        if (in.getCurrency() != null) r.setCurrency(in.getCurrency());
        if (in.getExchangeRate() != null) r.setExchangeRate(in.getExchangeRate());
        if (in.getRevenueFinal() != null) r.setRevenueFinal(in.getRevenueFinal());
        if (in.getRaw() != null) r.setRaw(in.getRaw());
        if (in.getSourceBusinessDate() != null) r.setSourceBusinessDate(in.getSourceBusinessDate());
        if (in.getExportAsOf() != null) r.setExportAsOf(in.getExportAsOf());
    }

    /** Everything qualification depends on; equal fingerprints mean a re-import needs no re-evaluation. */
    static String qualificationFingerprint(PmsReservation r) {
        return String.join("|", String.valueOf(r.getStatus()), String.valueOf(r.getArrival()), String.valueOf(r.getDeparture()),
                String.valueOf(r.getActualCheckOut()), Objects.toString(r.getMembershipNo(), ""), Objects.toString(r.getMembershipType(), ""),
                Objects.toString(r.getHolderPmsProfileId(), ""), Objects.toString(r.getHolderEmailNorm(), ""), Objects.toString(r.getHolderLastName(), ""),
                Objects.toString(r.getSourceCode(), ""), Objects.toString(r.getMarketCode(), ""), Objects.toString(r.getOriginCode(), ""),
                Objects.toString(r.getChannelCode(), ""), String.valueOf(r.getIsDayUse()), String.valueOf(r.getIsComplimentary()),
                String.valueOf(r.getIsHouseUse()), String.valueOf(r.getIsPseudoRoom()), Objects.toString(r.getContactId(), ""));
    }

    static String fullFingerprint(PmsReservation r) {
        return qualificationFingerprint(r) + "|" + Objects.toString(r.getRoomRevenue(), "") + "|" + Objects.toString(r.getFbRevenue(), "")
                + "|" + Objects.toString(r.getOtherRevenue(), "") + "|" + Objects.toString(r.getTotalRevenue(), "") + "|" + Objects.toString(r.getRoomNo(), "")
                + "|" + Objects.toString(r.getCompanyPmsProfileId(), "") + "|" + Objects.toString(r.getAgentPmsProfileId(), "")
                + "|" + Objects.toString(r.getGroupPmsProfileId(), "") + "|" + Objects.toString(r.getBookingDate(), "");
    }

    private static String eventFor(PmsReservation.Status before, PmsReservation.Status after) {
        if (before == after) {
            return EVENT_CHANGED;
        }
        return switch (after) {
            case IN_HOUSE -> EVENT_CHECKED_IN;
            case CHECKED_OUT -> EVENT_CHECKED_OUT;
            case CANCELLED -> EVENT_CANCELLED;
            case NO_SHOW -> EVENT_NO_SHOW;
            default -> EVENT_CHANGED;
        };
    }

    /** Holder, company, agent and group rows; accounts are linked by the PMS profile id they carry in CRM. */
    private void linkGuests(PmsReservation r) {
        guestRepo.deleteByReservationId(r.getId());
        List<PmsReservationGuest> rows = new ArrayList<>();
        rows.add(PmsReservationGuest.builder().tenantId(r.getTenantId()).reservationId(r.getId()).role(PmsReservationGuest.Role.HOLDER)
                .pmsProfileId(r.getHolderPmsProfileId()).contactId(r.getContactId())
                .firstName(r.getHolderFirstName()).lastName(r.getHolderLastName()).build());
        addAccountRow(rows, r, PmsReservationGuest.Role.COMPANY, r.getCompanyPmsProfileId());
        addAccountRow(rows, r, PmsReservationGuest.Role.AGENT, r.getAgentPmsProfileId());
        addAccountRow(rows, r, PmsReservationGuest.Role.GROUP, r.getGroupPmsProfileId());
        guestRepo.saveAll(rows);
    }

    private void addAccountRow(List<PmsReservationGuest> rows, PmsReservation r, PmsReservationGuest.Role role, String pmsProfileId) {
        if (pmsProfileId == null) {
            return;
        }
        Long accountId = accountRepo.findByTenantIdAndOperaProfileId(r.getTenantId(), pmsProfileId).map(CrmAccount::getId).orElse(null);
        rows.add(PmsReservationGuest.builder().tenantId(r.getTenantId()).reservationId(r.getId()).role(role)
                .pmsProfileId(pmsProfileId).accountId(accountId).build());
    }

    private void refreshStats(PmsReservation r, Long previousContactId) {
        Set<Long> contacts = new LinkedHashSet<>();
        if (previousContactId != null) contacts.add(previousContactId);
        if (r.getContactId() != null) contacts.add(r.getContactId());
        contacts.forEach(statsService::recomputeContact);
        statsService.recomputeAccountsForProfiles(r.getTenantId(), r.getCompanyPmsProfileId(), r.getAgentPmsProfileId(), r.getGroupPmsProfileId());
    }

    private void publish(PmsReservation r, String type) {
        ObjectNode data = publisher.newPayload();
        data.put("reservationId", r.getId());
        data.put("propertyTenantId", r.getPropertyTenantId());
        data.put("pmsSystem", r.getPmsSystem().name());
        data.put("pmsReservationId", r.getPmsReservationId());
        data.put("confirmationNo", r.getConfirmationNo());
        data.put("status", r.getStatus().name());
        data.put("arrival", r.getArrival().toString());
        data.put("departure", r.getDeparture().toString());
        data.put("nights", r.getNights());
        data.put("roomType", r.getRoomType());
        data.put("rateCode", r.getRateCode());
        data.put("sourceCode", r.getSourceCode());
        data.put("marketCode", r.getMarketCode());
        data.put("channelCode", r.getChannelCode());
        data.put("contactId", r.getContactId());
        data.put("membershipNo", r.getMembershipNo());
        data.put("companyPmsProfileId", r.getCompanyPmsProfileId());
        data.put("agentPmsProfileId", r.getAgentPmsProfileId());
        if (r.getTotalRevenue() != null) {
            ObjectNode rev = data.putObject("revenue");
            rev.put("room", r.getRoomRevenue());
            rev.put("fb", r.getFbRevenue());
            rev.put("other", r.getOtherRevenue());
            rev.put("tax", r.getTaxTotal());
            rev.put("total", r.getTotalRevenue());
            rev.put("currency", r.getCurrency());
            rev.put("final", Boolean.TRUE.equals(r.getRevenueFinal()));
        }
        ObjectNode holder = data.putObject("holder");
        holder.put("firstName", r.getHolderFirstName());
        holder.put("lastName", r.getHolderLastName());
        holder.put("email", r.getHolderEmailNorm());
        holder.put("pmsProfileId", r.getHolderPmsProfileId());
        publisher.publish(r.getTenantId(), type, "pms_reservation", r.getId(), data);
    }

    private PmsReservation require(Long tenantId, Long id) {
        return reservationRepo.findByIdAndTenantId(id, tenantId).orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
    }
}
