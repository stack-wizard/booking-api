package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmAssignmentRule;
import com.stackwizard.booking_api.repository.CrmAssignmentRuleRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class CrmAssignmentRuleService {
    private final CrmAssignmentRuleRepository repo;
    private final CrmTeamRepository teamRepo;
    private final CrmTeamDirectory teamDirectory;
    private final CrmSegmentService segmentService;
    private final CrmAccessContext accessContext;

    public CrmAssignmentRuleService(CrmAssignmentRuleRepository repo,
                                    CrmTeamRepository teamRepo,
                                    CrmTeamDirectory teamDirectory,
                                    CrmSegmentService segmentService,
                                    CrmAccessContext accessContext) {
        this.repo = repo;
        this.teamRepo = teamRepo;
        this.teamDirectory = teamDirectory;
        this.segmentService = segmentService;
        this.accessContext = accessContext;
    }

    public List<CrmAssignmentRule> findAll() {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        return repo.findByTenantIdOrderByPriorityAscIdAsc(TenantResolver.requireOrgTenantId());
    }

    @Transactional
    public CrmAssignmentRule create(CrmAssignmentRule rule) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireOrgTenantId();
        CrmAssignmentRule target = new CrmAssignmentRule();
        target.setTenantId(tenantId);
        apply(tenantId, target, rule);
        return repo.save(target);
    }

    @Transactional
    public CrmAssignmentRule update(Long id, CrmAssignmentRule changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireOrgTenantId();
        CrmAssignmentRule existing = repo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment rule not found: " + id));
        if (!java.util.Objects.equals(existing.getTargetTeamId(), changes.getTargetTeamId())) {
            existing.setLastAssignedUserId(null);
        }
        apply(tenantId, existing, changes);
        return repo.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        repo.delete(repo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Assignment rule not found: " + id)));
    }

    private void apply(Long tenantId, CrmAssignmentRule target, CrmAssignmentRule source) {
        if (!StringUtils.hasText(source.getName())) {
            throw new IllegalArgumentException("name is required");
        }
        if (source.getTargetTeamId() == null || teamRepo.findByIdAndTenantId(source.getTargetTeamId(), tenantId).isEmpty()) {
            throw new IllegalArgumentException("targetTeamId must be an existing team");
        }
        CrmAssignmentRule.Strategy strategy = source.getStrategy() != null ? source.getStrategy() : CrmAssignmentRule.Strategy.ROUND_ROBIN;
        if (strategy == CrmAssignmentRule.Strategy.FIXED_USER) {
            if (source.getFixedUserId() == null) {
                throw new IllegalArgumentException("FIXED_USER needs fixedUserId");
            }
            teamDirectory.requireUser(tenantId, source.getFixedUserId());
        }
        if (source.getMinPax() != null && source.getMaxPax() != null && source.getMinPax() > source.getMaxPax()) {
            throw new IllegalArgumentException("minPax must not exceed maxPax");
        }
        if (source.getMinPax() != null && source.getMinPax() < 0) {
            throw new IllegalArgumentException("minPax must be >= 0");
        }
        String country = trimToNull(source.getCountry());
        if (country != null && country.length() != 2) {
            throw new IllegalArgumentException("country must be an ISO 3166-1 alpha-2 code");
        }
        target.setName(source.getName().trim());
        target.setPriority(source.getPriority() != null ? source.getPriority() : 100);
        target.setActive(source.getActive() == null || source.getActive());
        target.setSegment(segmentService.normalize(tenantId, source.getSegment()));
        target.setCountry(country == null ? null : country.toUpperCase());
        target.setSource(trimToNull(source.getSource()));
        target.setInquiryType(trimToNull(source.getInquiryType()));
        target.setEventType(trimToNull(source.getEventType()));
        target.setMinPax(source.getMinPax());
        target.setMaxPax(source.getMaxPax());
        target.setTargetTeamId(source.getTargetTeamId());
        target.setStrategy(strategy);
        target.setFixedUserId(strategy == CrmAssignmentRule.Strategy.FIXED_USER ? source.getFixedUserId() : null);
        target.setUpdatedAt(OffsetDateTime.now());
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
