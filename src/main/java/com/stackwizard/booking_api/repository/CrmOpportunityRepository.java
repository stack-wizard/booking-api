package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmOpportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CrmOpportunityRepository extends JpaRepository<CrmOpportunity, Long> {
    Optional<CrmOpportunity> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select o from CrmOpportunity o
            where o.tenantId = :tenantId
              and (:pipelineId is null or o.pipelineId = :pipelineId)
              and (
                   :scopeAll = true
                   or (:scopeOwn = true and o.ownerUserId = :currentUserId)
                   or (:scopeTeam = true and o.ownerUserId in :teamUserIds)
              )
            order by o.createdAt desc
            """)
    List<CrmOpportunity> findScoped(@Param("tenantId") Long tenantId,
                                    @Param("pipelineId") Long pipelineId,
                                    @Param("scopeAll") boolean scopeAll,
                                    @Param("scopeOwn") boolean scopeOwn,
                                    @Param("scopeTeam") boolean scopeTeam,
                                    @Param("currentUserId") Long currentUserId,
                                    @Param("teamUserIds") Collection<Long> teamUserIds);
}
