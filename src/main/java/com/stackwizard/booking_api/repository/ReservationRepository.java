package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByRequestId(Long requestId);

    @Query("select distinct r from Reservation r join fetch r.requestedResource join fetch r.request req where req.id = :requestId order by r.id")
    List<Reservation> findByRequestIdWithDetails(@Param("requestId") Long requestId);

    @Query("select distinct r from Reservation r join fetch r.requestedResource join fetch r.request req where req.id in :requestIds order by req.id, r.id")
    List<Reservation> findByRequestIdsWithDetails(@Param("requestIds") Collection<Long> requestIds);

    boolean existsByRequestId(Long requestId);

    List<Reservation> findByTenantIdAndEventFunctionIdIn(Long tenantId, Collection<Long> eventFunctionIds);

    @Query("""
            select r from Reservation r
            where r.tenantId = :tenantId
              and r.eventFunctionId = :eventFunctionId
              and upper(r.status) <> 'CANCELLED'
            """)
    List<Reservation> findActiveByEventFunctionId(@Param("tenantId") Long tenantId,
                                                  @Param("eventFunctionId") Long eventFunctionId);
}
