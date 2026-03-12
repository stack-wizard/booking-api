package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.OperaFiscalTaxMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface OperaFiscalTaxMappingRepository extends JpaRepository<OperaFiscalTaxMapping, Long> {
    List<OperaFiscalTaxMapping> findByTenantId(Long tenantId);

    Optional<OperaFiscalTaxMapping> findByTenantIdAndTaxPercent(Long tenantId, BigDecimal taxPercent);
}
