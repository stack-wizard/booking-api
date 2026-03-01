package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.TenantPaymentProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantPaymentProviderConfigRepository extends JpaRepository<TenantPaymentProviderConfig, Long> {
    List<TenantPaymentProviderConfig> findByTenantId(Long tenantId);
    Optional<TenantPaymentProviderConfig> findByTenantIdAndProvider(Long tenantId, String provider);
}
