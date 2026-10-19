package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByIdAndTenantId(Long id, Long tenantId);

    List<Booking> findByTenantIdAndOpportunityIdOrderByDateFromAsc(Long tenantId, Long opportunityId);

    boolean existsByTenantIdAndStatusCodeIgnoreCase(Long tenantId, String statusCode);

    @Query("""
            select e from Booking e
            where e.tenantId = :tenantId
              and (:status is null or e.status = :status)
              and (:accountId is null or e.accountId = :accountId)
              and e.dateTo >= :from
              and e.dateFrom <= :to
              and (:hotelId is null or e.propertyTenantId is null or e.propertyTenantId = :hotelId)
              and (:all = true
                   or ((:own = true or :team = true) and e.ownerUserId = :currentUserId)
                   or (:team = true
                       and (e.ownerUserId in :teamUserIds or e.teamId in :teamIds)
                       and (:allHotels = true or e.propertyTenantId in :propertyIds)))
            order by e.dateFrom asc, e.id asc
            """)
    List<Booking> findScoped(@Param("tenantId") Long tenantId,
                           @Param("status") Booking.Status status,
                           @Param("accountId") Long accountId,
                           @Param("from") LocalDate from,
                           @Param("to") LocalDate to,
                           @Param("all") boolean all,
                           @Param("own") boolean own,
                           @Param("team") boolean team,
                           @Param("currentUserId") Long currentUserId,
                           @Param("teamUserIds") Collection<Long> teamUserIds,
                           @Param("teamIds") Collection<Long> teamIds,
                           @Param("allHotels") boolean allHotels,
                           @Param("propertyIds") Collection<Long> propertyIds,
                           @Param("hotelId") Long hotelId);

    @Query("""
            select e from Booking e
            where e.status = com.stackwizard.booking_api.model.Booking.Status.TENTATIVE
              and e.decisionDate < :today
            """)
    List<Booking> findExpiredTentative(@Param("today") LocalDate today);

    @Query("""
            select e from Booking e
            where e.status = com.stackwizard.booking_api.model.Booking.Status.TENTATIVE
              and e.decisionDate between :from and :to
            """)
    List<Booking> findDecisionDueBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select e from Booking e
            where e.status in (com.stackwizard.booking_api.model.Booking.Status.TENTATIVE,
                               com.stackwizard.booking_api.model.Booking.Status.DEFINITE)
              and e.guaranteedPax is null
              and e.guaranteeDueDate between :from and :to
            """)
    List<Booking> findGuaranteeDueBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select e from Booking e
            where e.status = com.stackwizard.booking_api.model.Booking.Status.DEFINITE
              and e.dateFrom between :from and :to
              and not exists (select o.id from BookingOrder o
                              where o.bookingId = e.id
                                and o.status = com.stackwizard.booking_api.model.BookingOrder.Status.ISSUED)
            """)
    List<Booking> findDefiniteWithoutBeoStartingBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select e from Booking e
            where e.tenantId = :tenantId
              and (e.primaryContactId in :contactIds or e.accountId in :accountIds)
            order by e.dateFrom desc, e.id desc
            """)
    List<Booking> findForPortal(@Param("tenantId") Long tenantId,
                              @Param("contactIds") Collection<Long> contactIds,
                              @Param("accountIds") Collection<Long> accountIds);

    List<Booking> findByTenantIdAndOwnerUserIdAndStatusInAndDateToGreaterThanEqual(Long tenantId, Long ownerUserId,
                                                                                Collection<Booking.Status> statuses,
                                                                                LocalDate dateTo);
}
