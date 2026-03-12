package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantIntegrationConfigRepository extends JpaRepository<TenantIntegrationConfig, Long> {
    List<TenantIntegrationConfig> findByTenantId(Long tenantId);

    Optional<TenantIntegrationConfig> findByTenantIdAndIntegrationTypeAndProvider(
            Long tenantId,
            String integrationType,
            String provider
    );
}
