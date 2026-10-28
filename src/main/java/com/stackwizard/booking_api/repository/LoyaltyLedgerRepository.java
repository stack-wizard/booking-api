package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoyaltyLedgerRepository extends JpaRepository<LoyaltyLedgerEntry, Long> {
    List<LoyaltyLedgerEntry> findByMemberIdOrderByIdDesc(Long memberId);

    Optional<LoyaltyLedgerEntry> findByIdAndTenantId(Long id, Long tenantId);

    List<LoyaltyLedgerEntry> findByReservationIdAndSourceAndActiveTrue(Long reservationId, LoyaltyLedgerEntry.Source source);

    boolean existsByMemberIdAndNightDateAndSourceAndActiveTrue(Long memberId, LocalDate nightDate, LoyaltyLedgerEntry.Source source);

    /** A night already credited to the member by another reservation (its own nights do not block a re-evaluation). */
    @Query("""
            select count(e) > 0 from LoyaltyLedgerEntry e
            where e.memberId = :memberId and e.nightDate = :nightDate and e.active = true
              and e.source = com.stackwizard.booking_api.model.LoyaltyLedgerEntry.Source.STAY
              and (e.reservationId is null or e.reservationId <> :reservationId)
            """)
    boolean nightCreditedElsewhere(Long memberId, LocalDate nightDate, Long reservationId);

    boolean existsByMemberIdAndSource(Long memberId, LoyaltyLedgerEntry.Source source);

    /** Active entries only: a reversed entry and its REVERSAL counter-entry are both inactive-neutral. */
    @Query("""
            select e from LoyaltyLedgerEntry e
            where e.memberId = :memberId and e.active = true
              and e.source <> com.stackwizard.booking_api.model.LoyaltyLedgerEntry.Source.REVERSAL
            """)
    List<LoyaltyLedgerEntry> findActiveBalanceEntries(Long memberId);
}
