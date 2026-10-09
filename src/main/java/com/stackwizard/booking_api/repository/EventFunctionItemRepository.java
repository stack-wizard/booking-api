package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.EventFunctionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventFunctionItemRepository extends JpaRepository<EventFunctionItem, Long> {
    Optional<EventFunctionItem> findByIdAndTenantId(Long id, Long tenantId);

    /** Items of functions of an event of the chain; their tenant is the hotel that hosts the function. */
    @Query("""
            select i from EventFunctionItem i
            where i.eventFunctionId in :functionIds
              and (i.tenantId = :orgTenantId
                   or i.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            """)
    List<EventFunctionItem> findForFunctions(@Param("orgTenantId") Long orgTenantId,
                                             @Param("functionIds") Collection<Long> functionIds);

    @Query("""
            select i from EventFunctionItem i
            where i.id = :id
              and (i.tenantId = :orgTenantId
                   or i.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            """)
    Optional<EventFunctionItem> findForOrgById(@Param("orgTenantId") Long orgTenantId, @Param("id") Long id);

    List<EventFunctionItem> findByTenantIdAndEventFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(Long tenantId, Long eventFunctionId);

    List<EventFunctionItem> findByTenantIdAndEventFunctionIdIn(Long tenantId, Collection<Long> eventFunctionIds);
}
