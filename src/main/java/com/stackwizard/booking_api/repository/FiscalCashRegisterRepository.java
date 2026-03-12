package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.FiscalCashRegister;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FiscalCashRegisterRepository extends JpaRepository<FiscalCashRegister, Long> {
    List<FiscalCashRegister> findByTenantId(Long tenantId);

    List<FiscalCashRegister> findByBusinessPremiseId(Long businessPremiseId);

    Optional<FiscalCashRegister> findByIdAndTenantId(Long id, Long tenantId);
}
