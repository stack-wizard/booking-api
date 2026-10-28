package com.stackwizard.booking_api.service.crm;

import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmAccountProduction;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.PmsReservation;
import com.stackwizard.booking_api.repository.CrmAccountProductionRepository;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.PmsReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CRM projections over {@code pms_reservation}: a guest's stay statistics and an account's (company / agency / group)
 * production per year and hotel. Recomputed from scratch per person / account, so they are always consistent with
 * the reservations and the PMS opening snapshot.
 */
@Service
public class CrmGuestStatsService {
    private final CrmContactRepository contactRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmAccountProductionRepository productionRepo;
    private final PmsReservationRepository reservationRepo;
    private final Clock clock;

    public CrmGuestStatsService(CrmContactRepository contactRepo,
                                CrmAccountRepository accountRepo,
                                CrmAccountProductionRepository productionRepo,
                                PmsReservationRepository reservationRepo,
                                Clock loyaltyClock) {
        this.contactRepo = contactRepo;
        this.accountRepo = accountRepo;
        this.productionRepo = productionRepo;
        this.reservationRepo = reservationRepo;
        this.clock = loyaltyClock;
    }

    /** Opening values from the PMS (stays before {@code pms_opening_as_of}) plus our reservations after that date. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeContact(Long contactId) {
        CrmContact c = contactRepo.findById(contactId).orElse(null);
        if (c == null) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate asOf = c.getPmsOpeningAsOf();
        int stays = c.getPmsOpeningStays() == null ? 0 : c.getPmsOpeningStays();
        int nights = c.getPmsOpeningNights() == null ? 0 : c.getPmsOpeningNights();
        BigDecimal revenue = c.getPmsOpeningRevenue() == null ? BigDecimal.ZERO : c.getPmsOpeningRevenue();
        BigDecimal roomRevenue = BigDecimal.ZERO;
        LocalDate first = dateFromStats(c, "firstStay");
        LocalDate last = dateFromStats(c, "lastStay");
        LocalDate nextArrival = null;
        Map<Long, Integer> nightsByProperty = new HashMap<>();
        for (PmsReservation r : reservationRepo.findForStats(contactId)) {
            if (r.getStatus() == PmsReservation.Status.RESERVED) {
                if (!r.getArrival().isBefore(today) && (nextArrival == null || r.getArrival().isBefore(nextArrival))) {
                    nextArrival = r.getArrival();
                }
                continue;
            }
            if (r.getStatus() != PmsReservation.Status.CHECKED_OUT) {
                continue;
            }
            if (asOf != null && !r.getDeparture().isAfter(asOf)) {
                continue; // already inside the PMS opening numbers
            }
            if (Boolean.TRUE.equals(r.getIsPseudoRoom())) {
                continue;
            }
            stays++;
            nights += r.getNights() == null ? 0 : r.getNights();
            revenue = revenue.add(nz(r.getTotalRevenue()));
            roomRevenue = roomRevenue.add(nz(r.getRoomRevenue()));
            if (first == null || r.getArrival().isBefore(first)) first = r.getArrival();
            if (last == null || r.getDeparture().isAfter(last)) last = r.getDeparture();
            nightsByProperty.merge(r.getPropertyTenantId(), r.getNights() == null ? 0 : r.getNights(), Integer::sum);
        }
        c.setStaysTotal(stays);
        c.setNightsTotal(nights);
        c.setRevenueTotal(revenue);
        c.setRoomRevenueTotal(roomRevenue);
        c.setFirstStay(first);
        c.setLastStay(last);
        c.setNextArrival(nextArrival);
        c.setFavoritePropertyTenantId(nightsByProperty.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(c.getFavoritePropertyTenantId()));
        c.setStatsUpdatedAt(OffsetDateTime.now(clock));
        contactRepo.save(c);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeAccountsForProfiles(Long tenantId, String... pmsProfileIds) {
        for (String profileId : pmsProfileIds) {
            if (profileId == null) {
                continue;
            }
            accountRepo.findByTenantIdAndOperaProfileId(tenantId, profileId).ifPresent(this::recomputeAccount);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeAccount(CrmAccount account) {
        String profileId = account.getOperaProfileId();
        if (profileId == null) {
            return;
        }
        Map<String, CrmAccountProduction> rows = new LinkedHashMap<>();
        for (PmsReservation r : reservationRepo.findCheckedOutForAccount(account.getTenantId(), profileId)) {
            if (Boolean.TRUE.equals(r.getIsPseudoRoom())) {
                continue;
            }
            CrmAccountProduction.Role role = Objects.equals(r.getCompanyPmsProfileId(), profileId) ? CrmAccountProduction.Role.COMPANY
                    : Objects.equals(r.getAgentPmsProfileId(), profileId) ? CrmAccountProduction.Role.AGENT : CrmAccountProduction.Role.GROUP;
            int year = r.getDeparture().getYear();
            String key = year + "|" + r.getPropertyTenantId() + "|" + role;
            CrmAccountProduction p = rows.computeIfAbsent(key, k -> CrmAccountProduction.builder()
                    .tenantId(account.getTenantId()).accountId(account.getId()).year(year).propertyTenantId(r.getPropertyTenantId()).role(role)
                    .stays(0).nights(0).roomRevenue(BigDecimal.ZERO).fbRevenue(BigDecimal.ZERO).otherRevenue(BigDecimal.ZERO)
                    .totalRevenue(BigDecimal.ZERO).updatedAt(OffsetDateTime.now(clock)).build());
            p.setStays(p.getStays() + 1);
            p.setNights(p.getNights() + (r.getNights() == null ? 0 : r.getNights()));
            p.setRoomRevenue(p.getRoomRevenue().add(nz(r.getRoomRevenue())));
            p.setFbRevenue(p.getFbRevenue().add(nz(r.getFbRevenue())));
            p.setOtherRevenue(p.getOtherRevenue().add(nz(r.getOtherRevenue())));
            p.setTotalRevenue(p.getTotalRevenue().add(nz(r.getTotalRevenue())));
        }
        productionRepo.deleteByAccountId(account.getId());
        productionRepo.flush();
        productionRepo.saveAll(rows.values());
    }

    public List<CrmAccountProduction> production(Long accountId) {
        return productionRepo.findByAccountIdOrderByYearDescPropertyTenantIdAsc(accountId);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static LocalDate dateFromStats(CrmContact c, String key) {
        if (c.getPmsStats() == null || !c.getPmsStats().hasNonNull(key)) {
            return null;
        }
        try {
            return LocalDate.parse(c.getPmsStats().get(key).asText());
        } catch (Exception ex) {
            return null;
        }
    }
}
