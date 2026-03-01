package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.TenantPaymentProviderConfig;
import com.stackwizard.booking_api.repository.TenantPaymentProviderConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TenantPaymentProviderConfigService {
    private final TenantPaymentProviderConfigRepository repo;

    public TenantPaymentProviderConfigService(TenantPaymentProviderConfigRepository repo) {
        this.repo = repo;
    }

    public List<TenantPaymentProviderConfig> findAll() {
        return repo.findAll();
    }

    public List<TenantPaymentProviderConfig> findByTenantId(Long tenantId) {
        return repo.findByTenantId(tenantId);
    }

    public Optional<TenantPaymentProviderConfig> findById(Long id) {
        return repo.findById(id);
    }

    public Optional<TenantPaymentProviderConfig> findByTenantIdAndProvider(Long tenantId, String provider) {
        if (tenantId == null || provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        return repo.findByTenantIdAndProvider(tenantId, provider.trim().toUpperCase(Locale.ROOT));
    }

    public TenantPaymentProviderConfig save(TenantPaymentProviderConfig config) {
        if (config.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (config.getProvider() == null || config.getProvider().isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        config.setProvider(config.getProvider().trim().toUpperCase(Locale.ROOT));
        if (config.getActive() == null) {
            config.setActive(Boolean.TRUE);
        }
        return repo.save(config);
    }

    public void deleteById(Long id) {
        repo.deleteById(id);
    }
}
