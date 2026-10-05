package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmLead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CrmLeadRepository extends JpaRepository<CrmLead, Long> {
    Optional<CrmLead> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select l from CrmLead l
            where l.tenantId = :tenantId
              and (
                   :scopeAll = true
                   or (:scopeOwn = true and l.ownerUserId = :currentUserId)
                   or (:scopeTeam = true and l.ownerUserId in :teamUserIds)
              )
            order by l.createdAt desc
            """)
    List<CrmLead> findScoped(@Param("tenantId") Long tenantId,
                             @Param("scopeAll") boolean scopeAll,
                             @Param("scopeOwn") boolean scopeOwn,
                             @Param("scopeTeam") boolean scopeTeam,
                             @Param("currentUserId") Long currentUserId,
                             @Param("teamUserIds") Collection<Long> teamUserIds);
}
