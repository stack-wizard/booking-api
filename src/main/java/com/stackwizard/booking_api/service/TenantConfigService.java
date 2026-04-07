package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.repository.TenantConfigRepository;
import org.springframework.stereotype.Service;

@Service
public class TenantConfigService {
    private static final int DEFAULT_HOLD_TTL_MINUTES = 15;
    private static final int DEFAULT_MANUAL_REVIEW_TTL_MINUTES = 48 * 60;

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

    public int manualReviewTtlMinutes(Long tenantId) {
        if (tenantId == null) {
            return DEFAULT_MANUAL_REVIEW_TTL_MINUTES;
        }
        return repo.findByTenantId(tenantId)
                .map(config -> config.getManualReviewTtlMinutes() != null
                        ? config.getManualReviewTtlMinutes()
                        : DEFAULT_MANUAL_REVIEW_TTL_MINUTES)
                .orElse(DEFAULT_MANUAL_REVIEW_TTL_MINUTES);
    }
}
