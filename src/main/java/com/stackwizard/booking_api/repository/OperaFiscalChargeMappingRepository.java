package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.OperaFiscalChargeMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperaFiscalChargeMappingRepository extends JpaRepository<OperaFiscalChargeMapping, Long> {
    List<OperaFiscalChargeMapping> findByTenantId(Long tenantId);

    Optional<OperaFiscalChargeMapping> findFirstByTenantIdAndActiveTrueAndProductIdOrderByPriorityAsc(Long tenantId, Long productId);

    Optional<OperaFiscalChargeMapping> findFirstByTenantIdAndActiveTrueAndProductIdIsNullAndProductTypeIgnoreCaseOrderByPriorityAsc(Long tenantId, String productType);

    Optional<OperaFiscalChargeMapping> findFirstByTenantIdAndActiveTrueAndProductIdIsNullAndProductTypeIsNullOrderByPriorityAsc(Long tenantId);
}
