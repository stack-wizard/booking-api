package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PriceProfileDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PriceProfileDateRepository extends JpaRepository<PriceProfileDate, Long> {
    @Query("""
            select d from PriceProfileDate d
            join fetch d.priceProfile prof
            where prof.tenantId = :tenantId
            order by prof.name, d.dateFrom
            """)
    List<PriceProfileDate> findForTenant(@Param("tenantId") Long tenantId);

    @Query("select d from PriceProfileDate d join fetch d.priceProfile where d.seasonId in :seasonIds order by d.dateFrom, d.id")
    List<PriceProfileDate> findBySeasonIds(@Param("seasonIds") java.util.Collection<Long> seasonIds);

    long countBySeasonId(Long seasonId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update PriceProfileDate d set d.seasonId = null where d.seasonId = :seasonId")
    int unlinkSeason(@Param("seasonId") Long seasonId);
}
