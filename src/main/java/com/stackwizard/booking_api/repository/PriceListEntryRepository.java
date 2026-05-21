package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.ReservationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PriceListEntryRepository extends JpaRepository<PriceListEntry, Long> {
    @Query("""
            select (count(p) > 0)
            from PriceListEntry p
            join p.priceProfile prof
            where p.productId = :productId
              and upper(p.uom) = upper(:uom)
              and upper(prof.currency) = upper(:currency)
            """)
    boolean existsByProductIdAndUomAndCurrency(@Param("productId") Long productId,
                                               @Param("uom") String uom,
                                               @Param("currency") String currency);

    @Query("""
            select p
            from PriceListEntry p
            join p.priceProfile prof
            join p.priceProfileDate pd
            where p.productId in :productIds
              and prof.tenantId = :tenantId
              and :date between pd.dateFrom and pd.dateTo
            """)
    List<PriceListEntry> findForProductsOnDate(@Param("productIds") List<Long> productIds,
                                               @Param("tenantId") Long tenantId,
                                               @Param("date") LocalDate date);

    @Query("""
            select p
            from PriceListEntry p
            join p.priceProfile prof
            join p.priceProfileDate pd
            where p.productId = :productId
              and upper(p.uom) = upper(:uom)
              and upper(prof.currency) = upper(:currency)
              and prof.tenantId = :tenantId
              and :date between pd.dateFrom and pd.dateTo
            """)
    List<PriceListEntry> findForProductUomOnDate(@Param("productId") Long productId,
                                                 @Param("uom") String uom,
                                                 @Param("currency") String currency,
                                                 @Param("tenantId") Long tenantId,
                                                 @Param("date") LocalDate date);

    @Query("""
            select p
            from PriceListEntry p
            join p.priceProfile prof
            join p.priceProfileDate pd
            where p.productId in :productIds
              and prof.tenantId = :tenantId
              and :date between pd.dateFrom and pd.dateTo
              and (prof.reservationRequestType is null or prof.reservationRequestType = :requestType)
            order by
              case when prof.reservationRequestType = :requestType then 0 else 1 end,
              case when p.startTime is null then 0 else 1 end,
              p.startTime,
              p.endTime,
              p.id
            """)
    List<PriceListEntry> findCandidatesForProductsOnDate(@Param("productIds") List<Long> productIds,
                                                         @Param("tenantId") Long tenantId,
                                                         @Param("date") LocalDate date,
                                                         @Param("requestType") ReservationRequest.Type requestType);

    @Query("""
            select p
            from PriceListEntry p
            join p.priceProfile prof
            join p.priceProfileDate pd
            where p.productId = :productId
              and upper(p.uom) = upper(:uom)
              and upper(prof.currency) = upper(:currency)
              and prof.tenantId = :tenantId
              and :date between pd.dateFrom and pd.dateTo
              and (prof.reservationRequestType is null or prof.reservationRequestType = :requestType)
            order by
              case when prof.reservationRequestType = :requestType then 0 else 1 end,
              case when p.startTime is null then 0 else 1 end,
              p.startTime,
              p.endTime,
              p.id
            """)
    List<PriceListEntry> findCandidatesForProductUomOnDate(@Param("productId") Long productId,
                                                           @Param("uom") String uom,
                                                           @Param("currency") String currency,
                                                           @Param("tenantId") Long tenantId,
                                                           @Param("date") LocalDate date,
                                                           @Param("requestType") ReservationRequest.Type requestType);
}
