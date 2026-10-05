package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmTeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CrmTeamMemberRepository extends JpaRepository<CrmTeamMember, Long> {
    List<CrmTeamMember> findByTenantIdAndTeamId(Long tenantId, Long teamId);
    Optional<CrmTeamMember> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select distinct m2.appUserId
            from CrmTeamMember m1
            join CrmTeamMember m2 on m2.teamId = m1.teamId and m2.tenantId = m1.tenantId
            where m1.tenantId = :tenantId and m1.appUserId = :userId
            """)
    List<Long> findTeamMateUserIds(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}
