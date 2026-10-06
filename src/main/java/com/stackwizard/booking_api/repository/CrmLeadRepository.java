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
                   or (:scopeTeam = true and l.teamId in :teamIds)
              )
            order by l.createdAt desc
            """)
    List<CrmLead> findScoped(@Param("tenantId") Long tenantId,
                             @Param("scopeAll") boolean scopeAll,
                             @Param("scopeOwn") boolean scopeOwn,
                             @Param("scopeTeam") boolean scopeTeam,
                             @Param("currentUserId") Long currentUserId,
                             @Param("teamUserIds") Collection<Long> teamUserIds,
                            @Param("teamIds") Collection<Long> teamIds);

    List<CrmLead> findByTenantIdAndOwnerUserIdAndStatusIn(Long tenantId, Long ownerUserId, Collection<CrmLead.Status> statuses);

    @Query("""
            select l.ownerUserId, count(l) from CrmLead l
            where l.tenantId = :tenantId and l.ownerUserId in :userIds and l.status in :statuses
            group by l.ownerUserId
            """)
    List<Object[]> countByOwner(@Param("tenantId") Long tenantId,
                                @Param("userIds") Collection<Long> userIds,
                                @Param("statuses") Collection<CrmLead.Status> statuses);
}
