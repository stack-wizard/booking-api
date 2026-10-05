package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmTeamRepository extends JpaRepository<CrmTeam, Long> {
    List<CrmTeam> findByTenantIdOrderByNameAsc(Long tenantId);
    Optional<CrmTeam> findByIdAndTenantId(Long id, Long tenantId);
}
