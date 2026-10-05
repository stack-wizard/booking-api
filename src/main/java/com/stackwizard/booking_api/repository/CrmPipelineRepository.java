package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmPipeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CrmPipelineRepository extends JpaRepository<CrmPipeline, Long> {
    List<CrmPipeline> findByTenantIdOrderByNameAsc(Long tenantId);
    Optional<CrmPipeline> findByIdAndTenantId(Long id, Long tenantId);
    Optional<CrmPipeline> findByTenantIdAndIsDefaultTrue(Long tenantId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update CrmPipeline p set p.isDefault = false
            where p.tenantId = :tenantId and p.isDefault = true
              and (:keepId is null or p.id <> :keepId)
            """)
    int clearDefault(@Param("tenantId") Long tenantId, @Param("keepId") Long keepId);
}
