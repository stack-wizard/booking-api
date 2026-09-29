package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.TenantConfig;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.TenantConfigRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@Service
public class PlatformTenantResolver {

    private static final int DEFAULT_HOLD_TTL_MINUTES = 15;
    private static final int DEFAULT_MANUAL_REVIEW_TTL_MINUTES = 2880;
    /** Placeholder hash for system users that never log in locally. */
    private static final String SYSTEM_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final PlatformTenantMappingRepository mappingRepo;
    private final TenantConfigRepository tenantConfigRepo;
    private final AppUserRepository appUserRepo;

    public PlatformTenantResolver(PlatformTenantMappingRepository mappingRepo,
                                  TenantConfigRepository tenantConfigRepo,
                                  AppUserRepository appUserRepo) {
        this.mappingRepo = mappingRepo;
        this.tenantConfigRepo = tenantConfigRepo;
        this.appUserRepo = appUserRepo;
    }

    @Transactional(readOnly = true)
    public Optional<Long> findBookingTenantId(UUID platformTenantId) {
        return mappingRepo.findByPlatformTenantId(platformTenantId).map(PlatformTenantMapping::getTenantId);
    }

    /**
     * Resolve an existing mapping only. Used for m2m tokens (never auto-provisions).
     */
    @Transactional(readOnly = true)
    public Long requireExisting(UUID platformTenantId) {
        return findBookingTenantId(platformTenantId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Platform tenant is not mapped to a booking tenant"));
    }

    /**
     * Resolve mapping, creating a new booking tenant when missing.
     * Only for authenticated interactive users with BOOKING entitlement.
     */
    @Transactional
    public Long resolveOrProvision(UUID platformTenantId) {
        Optional<Long> existing = findBookingTenantId(platformTenantId);
        if (existing.isPresent()) {
            return existing.get();
        }

        Long candidateTenantId = mappingRepo.nextTenantId();
        try {
            mappingRepo.saveAndFlush(PlatformTenantMapping.builder()
                    .tenantId(candidateTenantId)
                    .platformTenantId(platformTenantId)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            return mappingRepo.findByPlatformTenantId(platformTenantId)
                    .map(PlatformTenantMapping::getTenantId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "Failed to provision booking tenant mapping"));
        }

        ensureTenantDefaults(candidateTenantId);
        return candidateTenantId;
    }

    private void ensureTenantDefaults(Long tenantId) {
        if (tenantConfigRepo.findByTenantId(tenantId).isEmpty()) {
            tenantConfigRepo.save(TenantConfig.builder()
                    .tenantId(tenantId)
                    .holdTtlMinutes(DEFAULT_HOLD_TTL_MINUTES)
                    .manualReviewTtlMinutes(DEFAULT_MANUAL_REVIEW_TTL_MINUTES)
                    .build());
        }

        String onlineUsername = "online-system-tenant-" + tenantId;
        if (appUserRepo.findByTenantIdAndUsername(tenantId, onlineUsername).isEmpty()
                && appUserRepo.findByUsername(onlineUsername).isEmpty()) {
            appUserRepo.save(AppUser.builder()
                    .tenantId(tenantId)
                    .username(onlineUsername)
                    .passwordHash(SYSTEM_PASSWORD_HASH)
                    .role(AppUser.Role.CASHIER)
                    .build());
        }
    }
}
