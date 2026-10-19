package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByRequestId(Long requestId);

    @Query("select distinct r from Reservation r join fetch r.requestedResource join fetch r.request req where req.id = :requestId order by r.id")
    List<Reservation> findByRequestIdWithDetails(@Param("requestId") Long requestId);

    @Query("select distinct r from Reservation r join fetch r.requestedResource join fetch r.request req where req.id in :requestIds order by req.id, r.id")
    List<Reservation> findByRequestIdsWithDetails(@Param("requestIds") Collection<Long> requestIds);

    boolean existsByRequestId(Long requestId);

    List<Reservation> findByTenantIdAndBookingFunctionIdIn(Long tenantId, Collection<Long> bookingFunctionIds);

    List<Reservation> findByTenantIdAndBookingRoomBlockIdOrderByStartsAtAsc(Long tenantId, Long bookingRoomBlockId);

    @Query("""
            select r from Reservation r
            where r.tenantId = :tenantId
              and r.bookingFunctionId = :bookingFunctionId
              and upper(r.status) <> 'CANCELLED'
            """)
    List<Reservation> findActiveByBookingFunctionId(@Param("tenantId") Long tenantId,
                                                  @Param("bookingFunctionId") Long bookingFunctionId);

    /** Room-block hold lines in a window (used when room_inventory=OPERA and there is no local allocation). */
    @Query("""
            select distinct r from Reservation r
            join fetch r.requestedResource
            where r.tenantId = :tenantId
              and r.bookingRoomBlockId is not null
              and upper(r.status) <> 'CANCELLED'
              and r.startsAt < :end
              and r.endsAt > :start
            """)
    List<Reservation> findActiveRoomBlockLinesInWindow(@Param("tenantId") Long tenantId,
                                                       @Param("start") LocalDateTime start,
                                                       @Param("end") LocalDateTime end);
}
