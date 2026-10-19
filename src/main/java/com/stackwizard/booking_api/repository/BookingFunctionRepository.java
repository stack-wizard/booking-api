package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingFunction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingFunctionRepository extends JpaRepository<BookingFunction, Long> {
    Optional<BookingFunction> findByIdAndTenantId(Long id, Long tenantId);

    // An booking belongs to the chain, its functions to the hotel that hosts them. The "ForOrg" queries take the
    // chain tenant of the booking and match functions of the chain and of every hotel under it.

    @Query("""
            select f from BookingFunction f
            where f.bookingId = :bookingId
              and (f.tenantId = :orgTenantId
                   or f.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            order by f.startsAt asc, f.displayOrder asc, f.id asc
            """)
    List<BookingFunction> findForBooking(@Param("orgTenantId") Long orgTenantId, @Param("bookingId") Long bookingId);

    @Query("""
            select f from BookingFunction f
            where f.id = :id
              and (f.tenantId = :orgTenantId
                   or f.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            """)
    Optional<BookingFunction> findForOrgById(@Param("orgTenantId") Long orgTenantId, @Param("id") Long id);

    @Query("""
            select count(f) > 0 from BookingFunction f
            where f.bookingId = :bookingId and f.resourceId is not null
              and (f.tenantId = :orgTenantId
                   or f.tenantId in (select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            """)
    boolean existsSpaceFunctionForBooking(@Param("orgTenantId") Long orgTenantId, @Param("bookingId") Long bookingId);

    List<BookingFunction> findByTenantIdAndBookingIdOrderByStartsAtAscDisplayOrderAscIdAsc(Long tenantId, Long bookingId);

    List<BookingFunction> findByTenantIdAndIdIn(Long tenantId, Collection<Long> ids);

    boolean existsByTenantIdAndBookingIdAndResourceIdIsNotNull(Long tenantId, Long bookingId);

    List<BookingFunction> findByTenantIdAndResourceIdIsNotNullAndOccupancyStartsAtLessThanAndOccupancyEndsAtGreaterThan(
            Long tenantId, LocalDateTime to, LocalDateTime from);
}
