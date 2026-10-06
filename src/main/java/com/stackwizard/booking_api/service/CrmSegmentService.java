package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmSegment;
import com.stackwizard.booking_api.repository.CrmSegmentRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/** Segment catalogue. Once a tenant defines segments, account and lead segments must be one of them. */
@Service
public class CrmSegmentService {
    private final CrmSegmentRepository repo;
    private final CrmTeamRepository teamRepo;
    private final CrmAccessContext accessContext;

    public CrmSegmentService(CrmSegmentRepository repo, CrmTeamRepository teamRepo, CrmAccessContext accessContext) {
        this.repo = repo;
        this.teamRepo = teamRepo;
        this.accessContext = accessContext;
    }

    public List<CrmSegment> findAll() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return repo.findByTenantIdOrderByDisplayOrderAscNameAsc(TenantResolver.requireTenantId());
    }

    @Transactional
    public CrmSegment create(CrmSegment segment) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireTenantId();
        segment.setId(null);
        segment.setTenantId(tenantId);
        apply(tenantId, segment, segment);
        if (repo.findByTenantIdAndCodeIgnoreCase(tenantId, segment.getCode()).isPresent()) {
            throw new IllegalArgumentException("Segment code already exists: " + segment.getCode());
        }
        return repo.save(segment);
    }

    @Transactional
    public CrmSegment update(Long id, CrmSegment changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireTenantId();
        CrmSegment existing = repo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Segment not found: " + id));
        String oldCode = existing.getCode();
        apply(tenantId, existing, changes);
        if (!oldCode.equalsIgnoreCase(existing.getCode())) {
            throw new IllegalArgumentException("Segment code cannot be changed; accounts refer to it");
        }
        return repo.save(existing);
    }

    /** Canonical code for a free-text value; null for blank. */
    public String normalize(Long tenantId, String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        Optional<CrmSegment> match = repo.findByTenantIdAndCodeIgnoreCase(tenantId, value);
        if (match.isPresent()) {
            if (!Boolean.TRUE.equals(match.get().getActive())) {
                throw new IllegalArgumentException("Segment is inactive: " + value);
            }
            return match.get().getCode();
        }
        if (repo.existsByTenantIdAndActiveTrue(tenantId)) {
            throw new IllegalArgumentException("Unknown segment: " + value);
        }
        return value;
    }

    public Long defaultTeam(Long tenantId, String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        return repo.findByTenantIdAndCodeIgnoreCase(tenantId, code).map(CrmSegment::getDefaultTeamId).orElse(null);
    }

    private void apply(Long tenantId, CrmSegment target, CrmSegment source) {
        if (!StringUtils.hasText(source.getCode())) {
            throw new IllegalArgumentException("code is required");
        }
        if (!StringUtils.hasText(source.getName())) {
            throw new IllegalArgumentException("name is required");
        }
        if (source.getDefaultTeamId() != null && teamRepo.findByIdAndTenantId(source.getDefaultTeamId(), tenantId).isEmpty()) {
            throw new IllegalArgumentException("Team not found: " + source.getDefaultTeamId());
        }
        target.setCode(source.getCode().trim().toUpperCase());
        target.setName(source.getName().trim());
        target.setDefaultTeamId(source.getDefaultTeamId());
        target.setActive(source.getActive() == null || source.getActive());
        target.setDisplayOrder(source.getDisplayOrder() != null ? source.getDisplayOrder() : 0);
    }
}
