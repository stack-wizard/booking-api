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
                   or (:team = true and e.ownerUserId in :teamUserIds))
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
                           @Param("teamUserIds") Collection<Long> teamUserIds);

    @Query("""
            select e from Event e
            where e.status = com.stackwizard.booking_api.model.Event.Status.TENTATIVE
              and e.decisionDate < :today
            """)
    List<Event> findExpiredTentative(@Param("today") LocalDate today);
}
