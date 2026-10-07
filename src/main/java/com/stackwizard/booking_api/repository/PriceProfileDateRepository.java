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
}
