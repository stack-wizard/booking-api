package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.repository.TenantConfigRepository;
import org.springframework.stereotype.Service;

@Service
public class TenantConfigService {
    private static final int DEFAULT_HOLD_TTL_MINUTES = 15;

    private final TenantConfigRepository repo;

    public TenantConfigService(TenantConfigRepository repo) {
        this.repo = repo;
    }

    public int holdTtlMinutes(Long tenantId) {
        if (tenantId == null) {
            return DEFAULT_HOLD_TTL_MINUTES;
        }
        return repo.findByTenantId(tenantId)
                .map(config -> config.getHoldTtlMinutes() != null ? config.getHoldTtlMinutes() : DEFAULT_HOLD_TTL_MINUTES)
                .orElse(DEFAULT_HOLD_TTL_MINUTES);
    }
}
