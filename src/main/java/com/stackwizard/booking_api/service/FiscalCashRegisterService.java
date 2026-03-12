package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.FiscalBusinessPremise;
import com.stackwizard.booking_api.model.FiscalCashRegister;
import com.stackwizard.booking_api.repository.FiscalBusinessPremiseRepository;
import com.stackwizard.booking_api.repository.FiscalCashRegisterRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class FiscalCashRegisterService {
    private final FiscalCashRegisterRepository repo;
    private final FiscalBusinessPremiseRepository businessPremiseRepo;

    public FiscalCashRegisterService(FiscalCashRegisterRepository repo,
                                     FiscalBusinessPremiseRepository businessPremiseRepo) {
        this.repo = repo;
        this.businessPremiseRepo = businessPremiseRepo;
    }

    public List<FiscalCashRegister> findAll() {
        return repo.findAll();
    }

    public List<FiscalCashRegister> findByTenantId(Long tenantId) {
        return repo.findByTenantId(tenantId);
    }

    public List<FiscalCashRegister> findByBusinessPremiseId(Long businessPremiseId) {
        return repo.findByBusinessPremiseId(businessPremiseId);
    }

    public Optional<FiscalCashRegister> findById(Long id) {
        return repo.findById(id);
    }

    public FiscalCashRegister requireByIdAndTenantId(Long id, Long tenantId) {
        return repo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Fiscal cash register not found"));
    }

    public FiscalCashRegister save(FiscalCashRegister register) {
        normalizeAndValidate(register);

        FiscalBusinessPremise businessPremise = businessPremiseRepo.findById(register.getBusinessPremiseId())
                .orElseThrow(() -> new IllegalArgumentException("Fiscal business premise not found"));
        if (!businessPremise.getTenantId().equals(register.getTenantId())) {
            throw new IllegalArgumentException("cash register tenant must match business premise tenant");
        }

        register.setBusinessPremiseId(businessPremise.getId());
        return repo.save(register);
    }

    public void deleteById(Long id) {
        repo.deleteById(id);
    }

    private void normalizeAndValidate(FiscalCashRegister register) {
        if (register == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (register.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (register.getBusinessPremiseId() == null) {
            throw new IllegalArgumentException("businessPremiseId is required");
        }
        if (register.getCode() == null || register.getCode().isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        register.setCode(register.getCode().trim().toUpperCase(Locale.ROOT));

        if (register.getTerminalId() != null) {
            String normalizedTerminalId = register.getTerminalId().trim();
            register.setTerminalId(normalizedTerminalId.isEmpty() ? null : normalizedTerminalId);
        }

        if (register.getActive() == null) {
            register.setActive(Boolean.TRUE);
        }
    }
}
