package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findByIdAndTenantId(Long id, Long tenantId);

    List<Event> findByTenantIdAndOpportunityIdOrderByDateFromAsc(Long tenantId, Long opportunityId);

    @Query("""
            select e from Event e
            where e.tenantId = :tenantId
              and (:status is null or e.status = :status)
              and (:accountId is null or e.accountId = :accountId)
              and e.dateTo >= :from
              and e.dateFrom <= :to
              and (:all = true
                   or (:own = true and e.ownerUserId = :currentUserId)
                   or (:team = true and e.ownerUserId in :teamUserIds)
                   or (:team = true and e.teamId in :teamIds))
            order by e.dateFrom asc, e.id asc
            """)
    List<Event> findScoped(@Param("tenantId") Long tenantId,
                           @Param("status") Event.Status status,
                           @Param("accountId") Long accountId,
                           @Param("from") LocalDate from,
                           @Param("to") LocalDate to,
                           @Param("all") boolean all,
                           @Param("own") boolean own,
                           @Param("team") boolean team,
                           @Param("currentUserId") Long currentUserId,
                           @Param("teamUserIds") Collection<Long> teamUserIds,
                           @Param("teamIds") Collection<Long> teamIds);

    @Query("""
            select e from Event e
            where e.status = com.stackwizard.booking_api.model.Event.Status.TENTATIVE
              and e.decisionDate < :today
            """)
    List<Event> findExpiredTentative(@Param("today") LocalDate today);

    @Query("""
            select e from Event e
            where e.status = com.stackwizard.booking_api.model.Event.Status.TENTATIVE
              and e.decisionDate between :from and :to
            """)
    List<Event> findDecisionDueBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select e from Event e
            where e.status in (com.stackwizard.booking_api.model.Event.Status.TENTATIVE,
                               com.stackwizard.booking_api.model.Event.Status.DEFINITE)
              and e.guaranteedPax is null
              and e.guaranteeDueDate between :from and :to
            """)
    List<Event> findGuaranteeDueBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select e from Event e
            where e.status = com.stackwizard.booking_api.model.Event.Status.DEFINITE
              and e.dateFrom between :from and :to
              and not exists (select o.id from EventOrder o
                              where o.eventId = e.id
                                and o.status = com.stackwizard.booking_api.model.EventOrder.Status.ISSUED)
            """)
    List<Event> findDefiniteWithoutBeoStartingBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select e from Event e
            where e.tenantId = :tenantId
              and (e.primaryContactId in :contactIds or e.accountId in :accountIds)
            order by e.dateFrom desc, e.id desc
            """)
    List<Event> findForPortal(@Param("tenantId") Long tenantId,
                              @Param("contactIds") Collection<Long> contactIds,
                              @Param("accountIds") Collection<Long> accountIds);

    List<Event> findByTenantIdAndOwnerUserIdAndStatusInAndDateToGreaterThanEqual(Long tenantId, Long ownerUserId,
                                                                                Collection<Event.Status> statuses,
                                                                                LocalDate dateTo);
}
