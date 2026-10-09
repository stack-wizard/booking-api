package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Organization / hotel relations for code that holds a hotel tenant id but needs chain-level data (catalog,
 * CRM, events) or the other way round. A tenant without a mapping or without hotels is its own organization
 * and its own hotel, so legacy single-tenant data keeps working.
 */
@Service
public class TenantHierarchy {
    private final PlatformTenantMappingRepository mappingRepo;
    /** tenant id -> organization id. A parent is assigned once and never changes, so entries never go stale. */
    private final ConcurrentHashMap<Long, Long> orgCache = new ConcurrentHashMap<>();

    public TenantHierarchy(PlatformTenantMappingRepository mappingRepo) {
        this.mappingRepo = mappingRepo;
    }

    /** Organization that owns the shared data of {@code tenantId} (itself when it is an organization). */
    @Transactional(readOnly = true)
    public Long orgIdOf(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        Long cached = orgCache.get(tenantId);
        if (cached != null) {
            return cached;
        }
        Optional<PlatformTenantMapping> mapping = mappingRepo.findByTenantId(tenantId);
        if (mapping.isEmpty()) {
            return tenantId;
        }
        PlatformTenantMapping m = mapping.get();
        Long org = m.getKind() == PlatformTenantMapping.Kind.PROPERTY && m.getParentTenantId() != null
                ? m.getParentTenantId() : tenantId;
        orgCache.put(tenantId, org);
        return org;
    }

    /** Hotels of the organization; the organization itself when it has none (legacy single tenant). */
    @Transactional(readOnly = true)
    public List<Long> propertyIdsOf(Long orgTenantId) {
        List<Long> ids = mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(orgTenantId).stream()
                .map(PlatformTenantMapping::getTenantId)
                .toList();
        return ids.isEmpty() ? List.of(orgTenantId) : ids;
    }

    /** Organization plus all its hotels; used for rows owned by either level (users, shared reference data). */
    @Transactional(readOnly = true)
    public List<Long> familyIdsOf(Long orgTenantId) {
        List<Long> out = new ArrayList<>();
        out.add(orgTenantId);
        for (Long id : propertyIdsOf(orgTenantId)) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** True when {@code propertyTenantId} is a hotel of {@code orgTenantId} (or the same legacy tenant). */
    @Transactional(readOnly = true)
    public boolean isPropertyOf(Long propertyTenantId, Long orgTenantId) {
        if (propertyTenantId == null || orgTenantId == null) {
            return false;
        }
        return orgIdOf(propertyTenantId).equals(orgTenantId);
    }

    /** @throws IllegalArgumentException when the hotel is not part of the organization */
    public Long requirePropertyOf(Long propertyTenantId, Long orgTenantId) {
        if (!isPropertyOf(propertyTenantId, orgTenantId)) {
            throw new IllegalArgumentException("Hotel does not belong to this organization");
        }
        return propertyTenantId;
    }
}
