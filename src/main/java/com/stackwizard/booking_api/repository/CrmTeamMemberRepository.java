package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmTeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CrmTeamMemberRepository extends JpaRepository<CrmTeamMember, Long> {
    List<CrmTeamMember> findByTenantIdAndTeamIdOrderByValidFromDescIdDesc(Long tenantId, Long teamId);
    Optional<CrmTeamMember> findByIdAndTenantId(Long id, Long tenantId);
    Optional<CrmTeamMember> findByTenantIdAndTeamIdAndAppUserIdAndValidToIsNull(Long tenantId, Long teamId, Long appUserId);
    List<CrmTeamMember> findByTenantIdAndAppUserIdAndValidToIsNull(Long tenantId, Long appUserId);

    @Query("""
            select m from CrmTeamMember m
            where m.tenantId = :tenantId
              and m.validFrom <= :day
              and (m.validTo is null or m.validTo > :day)
            order by m.validFrom asc, m.id asc
            """)
    List<CrmTeamMember> findActive(@Param("tenantId") Long tenantId, @Param("day") LocalDate day);
}
