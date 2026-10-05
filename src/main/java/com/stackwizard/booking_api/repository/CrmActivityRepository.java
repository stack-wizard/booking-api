package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CrmActivityRepository extends JpaRepository<CrmActivity, Long> {
    Optional<CrmActivity> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select a from CrmActivity a
            where a.tenantId = :tenantId
              and (:accountId is null or a.accountId = :accountId)
              and (:opportunityId is null or a.opportunityId = :opportunityId)
              and (:leadId is null or a.leadId = :leadId)
              and (:assignedTo is null or a.assignedTo = :assignedTo)
              and (:openOnly = false or a.doneAt is null)
            order by a.createdAt desc
            """)
    List<CrmActivity> search(@Param("tenantId") Long tenantId,
                             @Param("accountId") Long accountId,
                             @Param("opportunityId") Long opportunityId,
                             @Param("leadId") Long leadId,
                             @Param("assignedTo") Long assignedTo,
                             @Param("openOnly") boolean openOnly);
}
