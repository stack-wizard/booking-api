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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlatformTenantResolver {

    public static final String TENANT_NOT_ONBOARDED = "TENANT_NOT_ONBOARDED";

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
     * Resolved request scope. {@code propertyTenantId} is null when the chain has several hotels and
     * none was selected (org-level request).
     */
    public record TenantScope(Long orgTenantId, Long propertyTenantId) {
        /** Tenant used for local user sync: the selected hotel, else the chain. */
        public Long effectiveTenantId() {
            return propertyTenantId != null ? propertyTenantId : orgTenantId;
        }
    }

    /**
     * Resolve the two-level scope from {@code X-Tenant-Id} (+ optional {@code X-Property-Id}).
     * <ul>
     *   <li>header is a PROPERTY: property = it, org = its parent (clients that still send the hotel UUID)</li>
     *   <li>header is an ORG: property from {@code X-Property-Id} (must be a child), else the only child hotel,
     *       else none; a chain without any child hotel works as its own property (legacy data)</li>
     * </ul>
     *
     * @param provisionIfMissing interactive users only; m2m tokens pass false
     */
    @Transactional
    public TenantScope resolveScope(UUID platformTenantId, UUID platformPropertyId, boolean provisionIfMissing) {
        PlatformTenantMapping mapping = mappingRepo.findByPlatformTenantId(platformTenantId).orElse(null);
        if (mapping == null) {
            if (!provisionIfMissing) {
                throw notOnboarded("Platform tenant is not registered in booking");
            }
            resolveOrProvision(platformTenantId);
            mapping = mappingRepo.findByPlatformTenantId(platformTenantId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "Failed to provision booking tenant mapping"));
        }
        requireUsable(mapping);

        if (mapping.getKind() == PlatformTenantMapping.Kind.PROPERTY) {
            if (platformPropertyId != null && !platformPropertyId.equals(platformTenantId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "X-Property-Id conflicts with X-Tenant-Id (X-Tenant-Id is already a hotel)");
            }
            return new TenantScope(mapping.getParentTenantId(), mapping.getTenantId());
        }

        Long orgTenantId = mapping.getTenantId();
        if (platformPropertyId != null) {
            PlatformTenantMapping property = mappingRepo.findByPlatformTenantId(platformPropertyId)
                    .orElseThrow(() -> notOnboarded("X-Property-Id is not registered in booking"));
            if (property.getKind() != PlatformTenantMapping.Kind.PROPERTY
                    || !orgTenantId.equals(property.getParentTenantId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "X-Property-Id is not a hotel of the selected organization");
            }
            requireUsable(property);
            return new TenantScope(orgTenantId, property.getTenantId());
        }

        List<PlatformTenantMapping> children =
                mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(orgTenantId);
        if (children.isEmpty()) {
            // Legacy chain registered before hotels existed: it is its own property.
            return new TenantScope(orgTenantId, orgTenantId);
        }
        List<PlatformTenantMapping> active = children.stream()
                .filter(c -> c.getStatus() == PlatformTenantMapping.Status.ACTIVE)
                .toList();
        if (active.size() == 1) {
            return new TenantScope(orgTenantId, active.get(0).getTenantId());
        }
        return new TenantScope(orgTenantId, null);
    }

    /**
     * Organization scope of an already registered Platform tenant, ignoring status. Used by the tenant setup
     * endpoints, which must work while a hotel is still in {@code SETUP}. Empty when not registered yet.
     */
    @Transactional(readOnly = true)
    public Optional<TenantScope> findOrgScopeForSetup(UUID platformTenantId) {
        return mappingRepo.findByPlatformTenantId(platformTenantId).map(m ->
                new TenantScope(m.getKind() == PlatformTenantMapping.Kind.PROPERTY
                        ? m.getParentTenantId() : m.getTenantId(), null));
    }

    private static void requireUsable(PlatformTenantMapping mapping) {
        if (mapping.getStatus() == PlatformTenantMapping.Status.SUSPENDED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant is suspended");
        }
        if (mapping.getStatus() == PlatformTenantMapping.Status.SETUP) {
            throw notOnboarded("Tenant setup is not finished");
        }
    }

    /** 409 whose message starts with {@link #TENANT_NOT_ONBOARDED}; booking-admin routes it to /setup. */
    public static ResponseStatusException notOnboarded(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, TENANT_NOT_ONBOARDED + ": " + detail);
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

    /** Default tenant_config and the online system user a booking tenant needs. Idempotent. */
    @Transactional
    public void ensureTenantDefaults(Long tenantId) {
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
