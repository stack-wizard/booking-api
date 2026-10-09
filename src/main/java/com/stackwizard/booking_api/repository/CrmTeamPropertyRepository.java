package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmTeamProperty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrmTeamPropertyRepository extends JpaRepository<CrmTeamProperty, Long> {
    List<CrmTeamProperty> findByTenantId(Long tenantId);

    List<CrmTeamProperty> findByTenantIdAndTeamId(Long tenantId, Long teamId);

    void deleteByTenantIdAndTeamId(Long tenantId, Long teamId);
}
