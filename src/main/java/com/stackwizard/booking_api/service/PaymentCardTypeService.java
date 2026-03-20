package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PaymentCardType;
import com.stackwizard.booking_api.repository.PaymentCardTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PaymentCardTypeService {
    private final PaymentCardTypeRepository repo;

    public PaymentCardTypeService(PaymentCardTypeRepository repo) {
        this.repo = repo;
    }

    public List<PaymentCardType> findAll(Long tenantId) {
        return tenantId == null ? repo.findAll() : repo.findByTenantIdOrderByCodeAscIdAsc(tenantId);
    }

    public PaymentCardType save(PaymentCardType cardType) {
        if (cardType == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (cardType.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String normalizedCode = normalizeCode(cardType.getCode());
        cardType.setCode(normalizedCode);
        cardType.setName(normalizeNullable(cardType.getName()));
        if (!StringUtils.hasText(cardType.getName())) {
            cardType.setName(normalizedCode);
        }
        if (cardType.getActive() == null) {
            cardType.setActive(Boolean.TRUE);
        }
        return repo.save(cardType);
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }

    public Optional<PaymentCardType> findByTenantIdAndCode(Long tenantId, String code) {
        if (tenantId == null || !StringUtils.hasText(code)) {
            return Optional.empty();
        }
        return repo.findByTenantIdAndCodeIgnoreCase(tenantId, normalizeCode(code));
    }

    public Optional<PaymentCardType> findActiveByTenantIdAndCode(Long tenantId, String code) {
        if (tenantId == null || !StringUtils.hasText(code)) {
            return Optional.empty();
        }
        return repo.findByTenantIdAndCodeIgnoreCaseAndActiveTrue(tenantId, normalizeCode(code));
    }

    public String requireKnownCode(Long tenantId, String code) {
        String normalizedCode = normalizeCode(code);
        return findByTenantIdAndCode(tenantId, normalizedCode)
                .map(PaymentCardType::getCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown cardType " + normalizedCode + " for tenant " + tenantId));
    }

    public String requireActiveCode(Long tenantId, String code) {
        String normalizedCode = normalizeCode(code);
        return findActiveByTenantIdAndCode(tenantId, normalizedCode)
                .map(PaymentCardType::getCode)
                .orElseThrow(() -> new IllegalArgumentException("Active cardType " + normalizedCode + " is not configured for tenant " + tenantId));
    }

    public String findActiveCodeOrNull(Long tenantId, String code) {
        if (tenantId == null || !StringUtils.hasText(code)) {
            return null;
        }
        return findActiveByTenantIdAndCode(tenantId, code)
                .map(PaymentCardType::getCode)
                .orElse(null);
    }

    private String normalizeCode(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("code is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
