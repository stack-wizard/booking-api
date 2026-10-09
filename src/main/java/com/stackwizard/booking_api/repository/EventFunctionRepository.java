package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.EventFunction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventFunctionRepository extends JpaRepository<EventFunction, Long> {
    Optional<EventFunction> findByIdAndTenantId(Long id, Long tenantId);

    // An event belongs to the chain, its functions to the hotel that hosts them. The "ForOrg" queries take the
    // chain tenant of the event and match functions of the chain and of every hotel under it.

    @Query("""
            select f from EventFunction f
            where f.eventId = :eventId
              and (f.tenantId = :orgTenantId
                   or f.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            order by f.startsAt asc, f.displayOrder asc, f.id asc
            """)
    List<EventFunction> findForEvent(@Param("orgTenantId") Long orgTenantId, @Param("eventId") Long eventId);

    @Query("""
            select f from EventFunction f
            where f.id = :id
              and (f.tenantId = :orgTenantId
                   or f.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            """)
    Optional<EventFunction> findForOrgById(@Param("orgTenantId") Long orgTenantId, @Param("id") Long id);

    @Query("""
            select count(f) > 0 from EventFunction f
            where f.eventId = :eventId and f.resourceId is not null
              and (f.tenantId = :orgTenantId
                   or f.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            """)
    boolean existsSpaceFunctionForEvent(@Param("orgTenantId") Long orgTenantId, @Param("eventId") Long eventId);

    List<EventFunction> findByTenantIdAndEventIdOrderByStartsAtAscDisplayOrderAscIdAsc(Long tenantId, Long eventId);

    List<EventFunction> findByTenantIdAndIdIn(Long tenantId, Collection<Long> ids);

    boolean existsByTenantIdAndEventIdAndResourceIdIsNotNull(Long tenantId, Long eventId);

    List<EventFunction> findByTenantIdAndResourceIdIsNotNullAndOccupancyStartsAtLessThanAndOccupancyEndsAtGreaterThan(
            Long tenantId, LocalDateTime to, LocalDateTime from);
}
