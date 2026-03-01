package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.DepositPolicy;
import com.stackwizard.booking_api.repository.DepositPolicyRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class DepositPolicyService {
    private final DepositPolicyRepository repo;

    public DepositPolicyService(DepositPolicyRepository repo) {
        this.repo = repo;
    }

    public List<DepositPolicy> findAll() {
        return repo.findAll();
    }

    public List<DepositPolicy> findByTenantId(Long tenantId) {
        return repo.findByTenantId(tenantId);
    }

    public Optional<DepositPolicy> findById(Long id) {
        return repo.findById(id);
    }

    public DepositPolicy save(DepositPolicy policy) {
        normalizeAndValidate(policy);
        return repo.save(policy);
    }

    public void deleteById(Long id) {
        repo.deleteById(id);
    }

    private void normalizeAndValidate(DepositPolicy policy) {
        if (policy.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (policy.getName() == null || policy.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (policy.getScopeType() == null || policy.getScopeType().isBlank()) {
            throw new IllegalArgumentException("scopeType is required");
        }
        if (policy.getDepositType() == null || policy.getDepositType().isBlank()) {
            throw new IllegalArgumentException("depositType is required");
        }
        if (policy.getDepositValue() == null) {
            throw new IllegalArgumentException("depositValue is required");
        }
        if (policy.getDepositValue().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("depositValue must be >= 0");
        }

        String scopeType = policy.getScopeType().trim().toUpperCase(Locale.ROOT);
        String depositType = policy.getDepositType().trim().toUpperCase(Locale.ROOT);
        policy.setScopeType(scopeType);
        policy.setDepositType(depositType);

        if (!scopeType.equals("TENANT") && !scopeType.equals("PRODUCT")) {
            throw new IllegalArgumentException("scopeType must be TENANT or PRODUCT");
        }
        if (!depositType.equals("PERCENT") && !depositType.equals("FIXED") && !depositType.equals("FULL")) {
            throw new IllegalArgumentException("depositType must be PERCENT, FIXED, or FULL");
        }

        if (scopeType.equals("TENANT")) {
            policy.setScopeId(null);
        } else if (policy.getScopeId() == null) {
            throw new IllegalArgumentException("scopeId is required for PRODUCT scope");
        }

        if (depositType.equals("PERCENT") && policy.getDepositValue().compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("depositValue must be <= 100 for PERCENT type");
        }
        if (depositType.equals("FULL")) {
            policy.setDepositValue(new BigDecimal("100"));
        }

        if (depositType.equals("FIXED")) {
            if (policy.getCurrency() == null || policy.getCurrency().isBlank()) {
                throw new IllegalArgumentException("currency is required for FIXED depositType");
            }
            policy.setCurrency(policy.getCurrency().trim().toUpperCase(Locale.ROOT));
        } else {
            policy.setCurrency(null);
        }

        if (policy.getActive() == null) {
            policy.setActive(Boolean.TRUE);
        }
        if (policy.getPriority() == null) {
            policy.setPriority(100);
        }
        if (policy.getEffectiveFrom() != null && policy.getEffectiveTo() != null
                && policy.getEffectiveTo().isBefore(policy.getEffectiveFrom())) {
            throw new IllegalArgumentException("effectiveTo must be >= effectiveFrom");
        }
    }
}
