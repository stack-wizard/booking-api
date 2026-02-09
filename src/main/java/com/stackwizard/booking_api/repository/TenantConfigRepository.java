package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.TenantConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantConfigRepository extends JpaRepository<TenantConfig, Long> {
    Optional<TenantConfig> findByTenantId(Long tenantId);
}
