package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.FiscalBusinessPremise;
import com.stackwizard.booking_api.repository.FiscalBusinessPremiseRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class FiscalBusinessPremiseService {
    private final FiscalBusinessPremiseRepository repo;

    public FiscalBusinessPremiseService(FiscalBusinessPremiseRepository repo) {
        this.repo = repo;
    }

    public List<FiscalBusinessPremise> findAll() {
        return repo.findAll();
    }

    public List<FiscalBusinessPremise> findByTenantId(Long tenantId) {
        return repo.findByTenantId(tenantId);
    }

    public Optional<FiscalBusinessPremise> findById(Long id) {
        return repo.findById(id);
    }

    public FiscalBusinessPremise requireByIdAndTenantId(Long id, Long tenantId) {
        return repo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Fiscal business premise not found"));
    }

    public FiscalBusinessPremise save(FiscalBusinessPremise premise) {
        normalizeAndValidate(premise);
        return repo.save(premise);
    }

    public void deleteById(Long id) {
        repo.deleteById(id);
    }

    private void normalizeAndValidate(FiscalBusinessPremise premise) {
        if (premise == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (premise.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (premise.getCode() == null || premise.getCode().isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
        if (premise.getName() == null || premise.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        premise.setCode(premise.getCode().trim().toUpperCase(Locale.ROOT));
        premise.setName(premise.getName().trim());
        if (premise.getActive() == null) {
            premise.setActive(Boolean.TRUE);
        }
    }
}
