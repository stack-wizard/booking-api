package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CrmAlertRepository extends JpaRepository<CrmAlert, Long> {
    boolean existsByTenantIdAndDedupeKey(Long tenantId, String dedupeKey);

    Optional<CrmAlert> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select a from CrmAlert a
            where a.tenantId = :tenantId and a.acknowledgedAt is null
              and (:all = true or a.assignedTo is null or a.assignedTo in :userIds)
            order by a.dueDate asc nulls last, a.id desc
            """)
    List<CrmAlert> findOpen(@Param("tenantId") Long tenantId,
                            @Param("all") boolean all,
                            @Param("userIds") Collection<Long> userIds);
}
