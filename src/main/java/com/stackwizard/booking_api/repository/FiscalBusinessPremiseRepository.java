package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.FiscalBusinessPremise;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FiscalBusinessPremiseRepository extends JpaRepository<FiscalBusinessPremise, Long> {
    List<FiscalBusinessPremise> findByTenantId(Long tenantId);

    Optional<FiscalBusinessPremise> findByIdAndTenantId(Long id, Long tenantId);
}
