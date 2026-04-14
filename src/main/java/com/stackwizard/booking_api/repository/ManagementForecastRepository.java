package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ReservationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Forecast / management aggregates. Every reservation query joins {@code reservation_request}
 * so rows are restricted to non-INTERNAL types and FINALIZED requests (plus caller-specific
 * predicates such as {@code confirmed_at} range).
 */
public interface ManagementForecastRepository extends JpaRepository<ReservationRequest, Long> {

    @Query("""
            select count(rr)
            from ReservationRequest rr
            where rr.tenantId = :tenantId
              and rr.status = :finalizedStatus
              and rr.type <> :internalType
              and rr.confirmedAt is not null
              and rr.confirmedAt >= :fromInclusive
              and rr.confirmedAt <= :toInclusive
            """)
    long countFinalizedRequests(
            @Param("tenantId") Long tenantId,
            @Param("finalizedStatus") ReservationRequest.Status finalizedStatus,
            @Param("internalType") ReservationRequest.Type internalType,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toInclusive") OffsetDateTime toInclusive);

    @Query("""
            select count(r)
            from Reservation r
            join r.request rr
            where rr.tenantId = :tenantId
              and rr.status = :finalizedStatus
              and rr.type <> :internalType
              and rr.confirmedAt is not null
              and rr.confirmedAt >= :fromInclusive
              and rr.confirmedAt <= :toInclusive
              and (r.status is null or upper(r.status) <> 'CANCELLED')
            """)
    long countForecastReservations(
            @Param("tenantId") Long tenantId,
            @Param("finalizedStatus") ReservationRequest.Status finalizedStatus,
            @Param("internalType") ReservationRequest.Type internalType,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toInclusive") OffsetDateTime toInclusive);

    @Query("""
            select coalesce(sum(coalesce(r.grossAmount, r.unitPrice * coalesce(r.qty, 1))), 0)
            from Reservation r
            join r.request rr
            where rr.tenantId = :tenantId
              and rr.status = :finalizedStatus
              and rr.type <> :internalType
              and rr.confirmedAt is not null
              and rr.confirmedAt >= :fromInclusive
              and rr.confirmedAt <= :toInclusive
              and (r.status is null or upper(r.status) <> 'CANCELLED')
            """)
    Object sumForecastGross(
            @Param("tenantId") Long tenantId,
            @Param("finalizedStatus") ReservationRequest.Status finalizedStatus,
            @Param("internalType") ReservationRequest.Type internalType,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toInclusive") OffsetDateTime toInclusive);

    @Query("""
            select r.productId, coalesce(p.name, ''), count(r),
                   coalesce(sum(coalesce(r.grossAmount, r.unitPrice * coalesce(r.qty, 1))), 0),
                   coalesce(sum(
                       coalesce(res.capTotal, 1)
                       * (case when r.qty is null or r.qty = 0 then 1 else r.qty end)
                   ), 0)
            from Reservation r
            join r.request rr
            join r.requestedResource res
            left join Product p on p.id = r.productId
            where rr.tenantId = :tenantId
              and rr.status = :finalizedStatus
              and rr.type <> :internalType
              and rr.confirmedAt is not null
              and rr.confirmedAt >= :fromInclusive
              and rr.confirmedAt <= :toInclusive
              and (r.status is null or upper(r.status) <> 'CANCELLED')
            group by r.productId, p.name
            order by coalesce(p.name, '') asc
            """)
    List<Object[]> aggregateReservationsByProduct(
            @Param("tenantId") Long tenantId,
            @Param("finalizedStatus") ReservationRequest.Status finalizedStatus,
            @Param("internalType") ReservationRequest.Type internalType,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toInclusive") OffsetDateTime toInclusive);
}
