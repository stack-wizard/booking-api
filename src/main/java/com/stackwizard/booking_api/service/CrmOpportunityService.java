package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.CrmStageChangeRequest;
import com.stackwizard.booking_api.model.CrmOpportunity;
import com.stackwizard.booking_api.model.CrmOutcomeReason;
import com.stackwizard.booking_api.model.CrmPipelineStage;
import com.stackwizard.booking_api.model.CrmStageRequirement;
import com.stackwizard.booking_api.model.CrmStageTransition;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmOutcomeReasonRepository;
import com.stackwizard.booking_api.repository.CrmPipelineStageRepository;
import com.stackwizard.booking_api.repository.CrmStageRequirementRepository;
import com.stackwizard.booking_api.repository.CrmStageTransitionRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmOwnerScope;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CrmOpportunityService {
    private final CrmOpportunityRepository opportunityRepo;
    private final CrmPipelineStageRepository stageRepo;
    private final CrmStageRequirementRepository requirementRepo;
    private final CrmOutcomeReasonRepository outcomeRepo;
    private final CrmStageTransitionRepository transitionRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final CrmAccessContext accessContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrmOpportunityService(CrmOpportunityRepository opportunityRepo,
                                 CrmPipelineStageRepository stageRepo,
                                 CrmStageRequirementRepository requirementRepo,
                                 CrmOutcomeReasonRepository outcomeRepo,
                                 CrmStageTransitionRepository transitionRepo,
                                 CrmAccountRepository accountRepo,
                                 CrmContactRepository contactRepo,
                                 CrmAccessContext accessContext) {
        this.opportunityRepo = opportunityRepo;
        this.stageRepo = stageRepo;
        this.requirementRepo = requirementRepo;
        this.outcomeRepo = outcomeRepo;
        this.transitionRepo = transitionRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.accessContext = accessContext;
    }

    public List<CrmOpportunity> findAll(Long pipelineId) {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return opportunityRepo.findScoped(
                TenantResolver.requireTenantId(), pipelineId,
                scope.all(), scope.own(), scope.team(), scope.currentUserId(), scope.teamUserIds());
    }

    public Optional<CrmOpportunity> findById(Long id) {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        return opportunityRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .filter(o -> CrmOwnerScope.from(accessContext).allows(o.getOwnerUserId()));
    }

    @Transactional
    public CrmOpportunity create(CrmOpportunity opportunity) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        opportunity.setId(null);
        opportunity.setTenantId(tenantId);
        if (opportunity.getCurrency() == null || opportunity.getCurrency().isBlank()) {
            opportunity.setCurrency("EUR");
        }
        opportunity.setStatus(CrmOpportunity.Status.OPEN);
        opportunity.setOutcomeReasonId(null);
        opportunity.setOutcomeNote(null);
        opportunity.setClosedAt(null);
        if (opportunity.getOwnerUserId() == null) {
            opportunity.setOwnerUserId(accessContext.currentUserId());
        }
        if (opportunity.getAttrs() == null) {
            opportunity.setAttrs(objectMapper.createObjectNode());
        }
        CrmPipelineStage stage = stageRepo.findByIdAndTenantId(opportunity.getStageId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Stage not found: " + opportunity.getStageId()));
        if (!stage.getPipelineId().equals(opportunity.getPipelineId())) {
            throw new IllegalArgumentException("Stage does not belong to pipeline");
        }
        if (stage.getStageKind() != CrmPipelineStage.StageKind.OPEN) {
            throw new IllegalArgumentException("Opportunity must be created in an open stage");
        }
        validateReferences(tenantId, opportunity.getAccountId(), opportunity.getPrimaryContactId());
        CrmOpportunity saved = opportunityRepo.save(opportunity);
        transitionRepo.save(CrmStageTransition.builder()
                .tenantId(tenantId)
                .opportunityId(saved.getId())
                .fromStageId(null)
                .toStageId(saved.getStageId())
                .changedBy(accessContext.currentUserId())
                .note("created")
                .build());
        return saved;
    }

    @Transactional
    public CrmOpportunity update(Long id, CrmOpportunity changes) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        CrmOpportunity existing = requireOwned(id);
        validateReferences(existing.getTenantId(), changes.getAccountId(), changes.getPrimaryContactId());
        existing.setName(changes.getName());
        existing.setAccountId(changes.getAccountId());
        existing.setPrimaryContactId(changes.getPrimaryContactId());
        existing.setAmount(changes.getAmount());
        if (changes.getCurrency() != null) {
            existing.setCurrency(changes.getCurrency());
        }
        existing.setExpectedCloseDate(changes.getExpectedCloseDate());
        existing.setSource(changes.getSource());
        if (changes.getOwnerUserId() != null) {
            existing.setOwnerUserId(changes.getOwnerUserId());
        }
        existing.setTeamId(changes.getTeamId());
        if (changes.getAttrs() != null) {
            existing.setAttrs(changes.getAttrs());
        }
        return opportunityRepo.save(existing);
    }

    @Transactional
    public CrmOpportunity changeStage(Long id, CrmStageChangeRequest request) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        if (request == null || request.getStageId() == null) {
            throw new IllegalArgumentException("stageId is required");
        }
        Long tenantId = TenantResolver.requireTenantId();
        CrmOpportunity opportunity = requireOwned(id);
        CrmPipelineStage target = stageRepo.findByIdAndTenantId(request.getStageId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Stage not found: " + request.getStageId()));
        if (!target.getPipelineId().equals(opportunity.getPipelineId())) {
            throw new IllegalArgumentException("Stage does not belong to the opportunity pipeline");
        }

        validateRequirements(tenantId, target.getId(), opportunity);

        if (target.getStageKind() == CrmPipelineStage.StageKind.WON
                || target.getStageKind() == CrmPipelineStage.StageKind.LOST) {
            accessContext.require(CrmPermission.OPPORTUNITY_CLOSE);
            if (request.getOutcomeReasonId() == null) {
                throw new IllegalArgumentException("outcomeReasonId is required for closed stages");
            }
            CrmOutcomeReason reason = outcomeRepo.findByIdAndTenantId(request.getOutcomeReasonId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Outcome reason not found"));
            // LOST stage can close as LOST, TURNED_DOWN or CANCELLED depending on reason kind
            if (target.getStageKind() == CrmPipelineStage.StageKind.WON && reason.getKind() != CrmOutcomeReason.Kind.WON) {
                throw new IllegalArgumentException("Outcome reason kind must be WON");
            }
            if (target.getStageKind() == CrmPipelineStage.StageKind.LOST
                    && reason.getKind() != CrmOutcomeReason.Kind.LOST
                    && reason.getKind() != CrmOutcomeReason.Kind.TURNED_DOWN
                    && reason.getKind() != CrmOutcomeReason.Kind.CANCELLED) {
                throw new IllegalArgumentException("Outcome reason kind must be LOST, TURNED_DOWN or CANCELLED");
            }
            opportunity.setOutcomeReasonId(reason.getId());
            opportunity.setOutcomeNote(request.getOutcomeNote());
            opportunity.setClosedAt(OffsetDateTime.now());
            opportunity.setStatus(switch (reason.getKind()) {
                case WON -> CrmOpportunity.Status.WON;
                case LOST -> CrmOpportunity.Status.LOST;
                case TURNED_DOWN -> CrmOpportunity.Status.TURNED_DOWN;
                case CANCELLED -> CrmOpportunity.Status.CANCELLED;
            });
        } else if (opportunity.getStatus() != CrmOpportunity.Status.OPEN) {
            opportunity.setStatus(CrmOpportunity.Status.OPEN);
            opportunity.setOutcomeReasonId(null);
            opportunity.setOutcomeNote(null);
            opportunity.setClosedAt(null);
        }

        Long fromStageId = opportunity.getStageId();
        opportunity.setStageId(target.getId());
        CrmOpportunity saved = opportunityRepo.save(opportunity);
        transitionRepo.save(CrmStageTransition.builder()
                .tenantId(tenantId)
                .opportunityId(saved.getId())
                .fromStageId(fromStageId)
                .toStageId(target.getId())
                .changedBy(accessContext.currentUserId())
                .note(request.getNote())
                .build());
        return saved;
    }

    public List<CrmStageTransition> transitions(Long opportunityId) {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        requireOwned(opportunityId);
        return transitionRepo.findByTenantIdAndOpportunityIdOrderByChangedAtAsc(
                TenantResolver.requireTenantId(), opportunityId);
    }

    void validateRequirements(Long tenantId, Long stageId, CrmOpportunity opportunity) {
        List<CrmStageRequirement> requirements = requirementRepo.findByTenantIdAndStageId(tenantId, stageId);
        for (CrmStageRequirement requirement : requirements) {
            Object value = resolveField(opportunity, requirement.getFieldPath());
            switch (requirement.getRequirement()) {
                case REQUIRED -> {
                    if (value == null || (value instanceof String s && s.isBlank())) {
                        throw new IllegalStateException(requirement.getMessage());
                    }
                }
                case MIN_VALUE -> {
                    if (value == null || toBigDecimal(value).compareTo(new BigDecimal(requirement.getValueSpec())) < 0) {
                        throw new IllegalStateException(requirement.getMessage());
                    }
                }
                case MAX_VALUE -> {
                    if (value == null || toBigDecimal(value).compareTo(new BigDecimal(requirement.getValueSpec())) > 0) {
                        throw new IllegalStateException(requirement.getMessage());
                    }
                }
            }
        }
    }

    private Object resolveField(CrmOpportunity opportunity, String fieldPath) {
        if (fieldPath == null) {
            return null;
        }
        if (fieldPath.startsWith("attrs.")) {
            String key = fieldPath.substring("attrs.".length());
            JsonNode attrs = opportunity.getAttrs();
            if (attrs == null || !attrs.has(key) || attrs.get(key).isNull()) {
                return null;
            }
            JsonNode node = attrs.get(key);
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isBoolean()) {
                return node.booleanValue();
            }
            return node.asText();
        }
        return switch (fieldPath) {
            case "amount" -> opportunity.getAmount();
            case "expectedCloseDate" -> opportunity.getExpectedCloseDate();
            case "contactId", "primaryContactId" -> opportunity.getPrimaryContactId();
            case "name" -> opportunity.getName();
            case "source" -> opportunity.getSource();
            default -> throw new IllegalArgumentException("Unsupported field_path: " + fieldPath);
        };
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }

    private void validateReferences(Long tenantId, Long accountId, Long contactId) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        accountRepo.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        if (contactId != null) {
            contactRepo.findByIdAndTenantId(contactId, tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactId));
        }
    }

    private CrmOpportunity requireOwned(Long id) {
        return findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Opportunity not found"));
    }
}
