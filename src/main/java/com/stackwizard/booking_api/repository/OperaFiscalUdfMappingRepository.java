package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.OperaFiscalUdfMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OperaFiscalUdfMappingRepository extends JpaRepository<OperaFiscalUdfMapping, Long> {
    List<OperaFiscalUdfMapping> findByTenantId(Long tenantId);

    List<OperaFiscalUdfMapping> findByTenantIdAndActiveTrueOrderBySortOrderAscIdAsc(Long tenantId);
}
