package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingFunctionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingFunctionItemRepository extends JpaRepository<BookingFunctionItem, Long> {
    Optional<BookingFunctionItem> findByIdAndTenantId(Long id, Long tenantId);

    /** Items of functions of an booking of the chain; their tenant is the hotel that hosts the function. */
    @Query("""
            select i from BookingFunctionItem i
            where i.bookingFunctionId in :functionIds
              and (i.tenantId = :orgTenantId
                   or i.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            """)
    List<BookingFunctionItem> findForFunctions(@Param("orgTenantId") Long orgTenantId,
                                             @Param("functionIds") Collection<Long> functionIds);

    @Query("""
            select i from BookingFunctionItem i
            where i.id = :id
              and (i.tenantId = :orgTenantId
                   or i.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            """)
    Optional<BookingFunctionItem> findForOrgById(@Param("orgTenantId") Long orgTenantId, @Param("id") Long id);

    List<BookingFunctionItem> findByTenantIdAndBookingFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(Long tenantId, Long bookingFunctionId);

    List<BookingFunctionItem> findByTenantIdAndBookingFunctionIdIn(Long tenantId, Collection<Long> bookingFunctionIds);
}
