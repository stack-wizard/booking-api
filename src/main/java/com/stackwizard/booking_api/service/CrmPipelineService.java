package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmOutcomeReason;
import com.stackwizard.booking_api.model.CrmPipeline;
import com.stackwizard.booking_api.model.CrmPipelineStage;
import com.stackwizard.booking_api.model.CrmStageRequirement;
import com.stackwizard.booking_api.repository.CrmOutcomeReasonRepository;
import com.stackwizard.booking_api.repository.CrmPipelineRepository;
import com.stackwizard.booking_api.repository.CrmPipelineStageRepository;
import com.stackwizard.booking_api.repository.CrmStageRequirementRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class CrmPipelineService {
    private final CrmPipelineRepository pipelineRepo;
    private final CrmPipelineStageRepository stageRepo;
    private final CrmStageRequirementRepository requirementRepo;
    private final CrmOutcomeReasonRepository outcomeRepo;
    private final CrmAccessContext accessContext;

    public CrmPipelineService(CrmPipelineRepository pipelineRepo,
                              CrmPipelineStageRepository stageRepo,
                              CrmStageRequirementRepository requirementRepo,
                              CrmOutcomeReasonRepository outcomeRepo,
                              CrmAccessContext accessContext) {
        this.pipelineRepo = pipelineRepo;
        this.stageRepo = stageRepo;
        this.requirementRepo = requirementRepo;
        this.outcomeRepo = outcomeRepo;
        this.accessContext = accessContext;
    }

    public List<CrmPipeline> findAll() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return pipelineRepo.findByTenantIdOrderByNameAsc(TenantResolver.requireTenantId());
    }

    public Optional<CrmPipeline> findById(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return pipelineRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId());
    }

    @Transactional
    public CrmPipeline create(CrmPipeline pipeline) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        pipeline.setId(null);
        pipeline.setTenantId(TenantResolver.requireTenantId());
        if (pipeline.getIsDefault() == null) {
            pipeline.setIsDefault(false);
        }
        if (pipeline.getActive() == null) {
            pipeline.setActive(true);
        }
        return pipelineRepo.save(pipeline);
    }

    @Transactional
    public CrmPipeline update(Long id, CrmPipeline changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmPipeline existing = pipelineRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + id));
        existing.setCode(changes.getCode());
        existing.setName(changes.getName());
        existing.setDescription(changes.getDescription());
        if (changes.getIsDefault() != null) {
            existing.setIsDefault(changes.getIsDefault());
        }
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        return pipelineRepo.save(existing);
    }

    public List<CrmPipelineStage> stages(Long pipelineId) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        Long tenantId = TenantResolver.requireTenantId();
        pipelineRepo.findByIdAndTenantId(pipelineId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + pipelineId));
        return stageRepo.findByTenantIdAndPipelineIdOrderByDisplayOrderAsc(tenantId, pipelineId);
    }

    @Transactional
    public CrmPipelineStage createStage(Long pipelineId, CrmPipelineStage stage) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireTenantId();
        pipelineRepo.findByIdAndTenantId(pipelineId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + pipelineId));
        stage.setId(null);
        stage.setTenantId(tenantId);
        stage.setPipelineId(pipelineId);
        if (stage.getProbability() == null) {
            stage.setProbability(BigDecimal.ZERO);
        }
        if (stage.getStageKind() == null) {
            stage.setStageKind(CrmPipelineStage.StageKind.OPEN);
        }
        return stageRepo.save(stage);
    }

    @Transactional
    public CrmPipelineStage updateStage(Long stageId, CrmPipelineStage changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmPipelineStage existing = stageRepo.findByIdAndTenantId(stageId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Stage not found: " + stageId));
        existing.setCode(changes.getCode());
        existing.setName(changes.getName());
        existing.setDisplayOrder(changes.getDisplayOrder());
        if (changes.getProbability() != null) {
            existing.setProbability(changes.getProbability());
        }
        if (changes.getStageKind() != null) {
            existing.setStageKind(changes.getStageKind());
        }
        return stageRepo.save(existing);
    }

    public List<CrmStageRequirement> requirements(Long stageId) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return requirementRepo.findByTenantIdAndStageId(TenantResolver.requireTenantId(), stageId);
    }

    @Transactional
    public CrmStageRequirement createRequirement(Long stageId, CrmStageRequirement requirement) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireTenantId();
        stageRepo.findByIdAndTenantId(stageId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Stage not found: " + stageId));
        requirement.setId(null);
        requirement.setTenantId(tenantId);
        requirement.setStageId(stageId);
        if (requirement.getRequirement() == null) {
            requirement.setRequirement(CrmStageRequirement.Requirement.REQUIRED);
        }
        return requirementRepo.save(requirement);
    }

    @Transactional
    public void deleteRequirement(Long id) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmStageRequirement existing = requirementRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Requirement not found: " + id));
        requirementRepo.delete(existing);
    }

    public List<CrmOutcomeReason> outcomeReasons() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return outcomeRepo.findByTenantIdOrderByDisplayOrderAsc(TenantResolver.requireTenantId());
    }

    @Transactional
    public CrmOutcomeReason createOutcomeReason(CrmOutcomeReason reason) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        reason.setId(null);
        reason.setTenantId(TenantResolver.requireTenantId());
        if (reason.getDisplayOrder() == null) {
            reason.setDisplayOrder(0);
        }
        if (reason.getActive() == null) {
            reason.setActive(true);
        }
        return outcomeRepo.save(reason);
    }

    @Transactional
    public CrmOutcomeReason updateOutcomeReason(Long id, CrmOutcomeReason changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmOutcomeReason existing = outcomeRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Outcome reason not found: " + id));
        existing.setKind(changes.getKind());
        existing.setCode(changes.getCode());
        existing.setName(changes.getName());
        if (changes.getDisplayOrder() != null) {
            existing.setDisplayOrder(changes.getDisplayOrder());
        }
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        return outcomeRepo.save(existing);
    }
}
