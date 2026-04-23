package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Stay dashboard aggregates: stay counts by reservation overlap vs request status, and
 * invoice line revenue excluding deposit documents.
 */
public interface ManagementStayDashboardRepository extends JpaRepository<Reservation, Long> {

    @Query("""
            select count(distinct rr.id)
            from Reservation r
            join r.request rr
            where rr.tenantId = :tenantId
              and rr.type <> :internalType
              and rr.status = :status
              and (r.status is null or upper(r.status) <> 'CANCELLED')
              and r.startsAt <= :rangeEnd
              and r.endsAt >= :rangeStart
            """)
    long countDistinctRequestsOverlappingStay(
            @Param("tenantId") Long tenantId,
            @Param("internalType") ReservationRequest.Type internalType,
            @Param("status") ReservationRequest.Status status,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    @Query("""
            select count(r)
            from Reservation r
            join r.request rr
            where rr.tenantId = :tenantId
              and rr.type <> :internalType
              and rr.status = :status
              and (r.status is null or upper(r.status) <> 'CANCELLED')
              and r.startsAt <= :rangeEnd
              and r.endsAt >= :rangeStart
            """)
    long countReservationsOverlappingStay(
            @Param("tenantId") Long tenantId,
            @Param("internalType") ReservationRequest.Type internalType,
            @Param("status") ReservationRequest.Status status,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    @Query("""
            select coalesce(sum(ii.grossAmount), 0)
            from InvoiceItem ii
            join ii.invoice i
            where i.tenantId = :tenantId
              and i.reservationRequestId is not null
              and i.status = 'ISSUED'
              and i.invoiceType not in (com.stackwizard.booking_api.model.InvoiceType.DEPOSIT,
                                        com.stackwizard.booking_api.model.InvoiceType.DEPOSIT_STORNO)
              and i.invoiceDate >= :fromDate
              and i.invoiceDate <= :toDate
            """)
    Object sumInvoiceLineGross(
            @Param("tenantId") Long tenantId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    @Query("""
            select ii.productId, min(ii.productName), count(ii.id),
                   coalesce(sum(ii.grossAmount), 0), coalesce(sum(ii.quantity), 0)
            from InvoiceItem ii
            join ii.invoice i
            where i.tenantId = :tenantId
              and i.reservationRequestId is not null
              and i.status = 'ISSUED'
              and i.invoiceType not in (com.stackwizard.booking_api.model.InvoiceType.DEPOSIT,
                                        com.stackwizard.booking_api.model.InvoiceType.DEPOSIT_STORNO)
              and i.invoiceDate >= :fromDate
              and i.invoiceDate <= :toDate
            group by ii.productId
            order by min(ii.productName) asc
            """)
    List<Object[]> aggregateInvoiceLinesByProduct(
            @Param("tenantId") Long tenantId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * Same invoice line filters as {@link #aggregateInvoiceLinesByProduct}, grouped by guest country on the invoice's
     * reservation request.
     */
    @Query(value = """
            select rr.customer_country,
                   count(ii.id),
                   coalesce(sum(ii.gross_amount), 0),
                   coalesce(sum(ii.quantity), 0)
            from invoice_item ii
            join invoice i on i.id = ii.invoice_id
            join reservation_request rr on rr.id = i.reservation_request_id
            where i.tenant_id = :tenantId
              and i.reservation_request_id is not null
              and i.status = 'ISSUED'
              and i.invoice_type not in ('DEPOSIT', 'DEPOSIT_STORNO')
              and i.invoice_date >= :fromDate
              and i.invoice_date <= :toDate
            group by rr.customer_country
            order by rr.customer_country
            """, nativeQuery = true)
    List<Object[]> aggregateInvoiceLinesByCountry(
            @Param("tenantId") Long tenantId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);
}
