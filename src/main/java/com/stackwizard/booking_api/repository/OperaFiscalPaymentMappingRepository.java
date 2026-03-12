package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.OperaFiscalPaymentMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperaFiscalPaymentMappingRepository extends JpaRepository<OperaFiscalPaymentMapping, Long> {
    List<OperaFiscalPaymentMapping> findByTenantId(Long tenantId);

    Optional<OperaFiscalPaymentMapping> findByTenantIdAndPaymentTypeIgnoreCase(Long tenantId, String paymentType);
}
