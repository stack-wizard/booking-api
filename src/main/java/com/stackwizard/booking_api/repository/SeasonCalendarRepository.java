package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.SeasonCalendar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SeasonCalendarRepository extends JpaRepository<SeasonCalendar, Long> {
    List<SeasonCalendar> findByTenantIdOrderByNameAscIdAsc(Long tenantId);

    Optional<SeasonCalendar> findByIdAndTenantId(Long id, Long tenantId);

    /** Calendars that apply to a hotel: its own first, then the chain's. */
    @Query("""
            select c from SeasonCalendar c
            where c.propertyTenantId = :propertyTenantId
               or (c.propertyTenantId is null and c.tenantId = :orgTenantId)
            order by case when c.propertyTenantId is null then 1 else 0 end, c.id
            """)
    List<SeasonCalendar> findApplicable(@Param("propertyTenantId") Long propertyTenantId,
                                        @Param("orgTenantId") Long orgTenantId);
}
