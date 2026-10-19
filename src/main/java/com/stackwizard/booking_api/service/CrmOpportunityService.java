package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.stackwizard.booking_api.dto.CrmStageChangeRequest;
import com.stackwizard.booking_api.model.CrmAccount;
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
import java.util.Objects;
import java.util.Optional;

@Service
public class CrmOpportunityService {
    /** Inquiry wishes in {@code attrs}; the admin keeps the same list. */
    public static final List<String> INTAKE_KEYS = List.of(
            "inquiryType", "eventType", "startDate", "endDate", "pax", "setupStyle", "roomNights",
            "catering", "dietary", "services", "agenda", "estimatedValue");

    private final CrmOpportunityRepository opportunityRepo;
    private final CrmPipelineStageRepository stageRepo;
    private final CrmStageRequirementRepository requirementRepo;
    private final CrmOutcomeReasonRepository outcomeRepo;
    private final CrmStageTransitionRepository transitionRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final CrmAccessContext accessContext;
    private final CrmTeamDirectory teamDirectory;
    private final TenantHierarchy hierarchy;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrmOpportunityService(CrmOpportunityRepository opportunityRepo,
                                 CrmPipelineStageRepository stageRepo,
                                 CrmStageRequirementRepository requirementRepo,
                                 CrmOutcomeReasonRepository outcomeRepo,
                                 CrmStageTransitionRepository transitionRepo,
                                 CrmAccountRepository accountRepo,
                                 CrmContactRepository contactRepo,
                                 CrmAccessContext accessContext,
                                 CrmTeamDirectory teamDirectory,
                                 TenantHierarchy hierarchy,
                                 BookingService bookingService) {
        this.opportunityRepo = opportunityRepo;
        this.stageRepo = stageRepo;
        this.requirementRepo = requirementRepo;
        this.outcomeRepo = outcomeRepo;
        this.transitionRepo = transitionRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.accessContext = accessContext;
        this.teamDirectory = teamDirectory;
        this.hierarchy = hierarchy;
        this.bookingService = bookingService;
    }

    private void requireHotelOfChain(Long orgTenantId, Long propertyTenantId) {
        if (propertyTenantId != null) {
            hierarchy.requirePropertyOf(propertyTenantId, orgTenantId);
        }
    }

    public List<CrmOpportunity> findAll(Long pipelineId) {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return opportunityRepo.findScoped(
                TenantResolver.requireOrgTenantId(), pipelineId,
                scope.all(), scope.own(), scope.team(), scope.currentUserId(), scope.teamUserIds(), scope.teamIds(),
                scope.allHotels(), scope.propertyIds(), TenantResolver.selectedHotelId());
    }

    public Optional<CrmOpportunity> findById(Long id) {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        return opportunityRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .filter(o -> CrmOwnerScope.from(accessContext).allows(o.getOwnerUserId(), o.getTeamId(), o.getPropertyTenantId()));
    }

    @Transactional
    public CrmOpportunity create(CrmOpportunity opportunity) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        Long tenantId = TenantResolver.requireOrgTenantId();
        opportunity.setId(null);
        opportunity.setTenantId(tenantId);
        requireHotelOfChain(tenantId, opportunity.getPropertyTenantId());
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
        Long accountTeam = opportunity.getAccountId() == null ? null
                : accountRepo.findByIdAndTenantId(opportunity.getAccountId(), tenantId).map(CrmAccount::getTeamId).orElse(null);
        opportunity.setTeamId(teamDirectory.resolveTeam(tenantId, opportunity.getOwnerUserId(), opportunity.getTeamId(), accountTeam));
        teamDirectory.requireAssignable(tenantId, opportunity.getOwnerUserId(), opportunity.getTeamId());
        opportunity.setPropertyTenantId(teamDirectory.resolveProperty(tenantId, opportunity.getTeamId(), opportunity.getPropertyTenantId()));
        validateAgency(tenantId, opportunity.getAgencyAccountId(), opportunity.getAccountId());
        CrmPipelineStage stage = stageRepo.findByIdAndTenantId(opportunity.getStageId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Stage not found: " + opportunity.getStageId()));
        if (!stage.getPipelineId().equals(opportunity.getPipelineId())) {
            throw new IllegalArgumentException("Stage does not belong to pipeline");
        }
        if (stage.getStageKind() != CrmPipelineStage.StageKind.OPEN) {
            throw new IllegalArgumentException("Opportunity must be created in an open stage");
        }
        opportunity.setIntakeLockedAt(locksIntake(stage, stageRepo.findByTenantIdAndPipelineIdOrderByDisplayOrderAsc(tenantId, stage.getPipelineId()))
                ? OffsetDateTime.now() : null);
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
        requireHotelOfChain(existing.getTenantId(), changes.getPropertyTenantId());
        boolean propertyChanged = !Objects.equals(changes.getPropertyTenantId(), existing.getPropertyTenantId());
        existing.setPropertyTenantId(changes.getPropertyTenantId());
        Long owner = changes.getOwnerUserId() != null ? changes.getOwnerUserId() : existing.getOwnerUserId();
        boolean ownerChanged = !Objects.equals(owner, existing.getOwnerUserId());
        Long team = changes.getTeamId() != null ? changes.getTeamId()
                : ownerChanged ? teamDirectory.resolveTeam(existing.getTenantId(), owner, null, existing.getTeamId())
                : existing.getTeamId();
        if (ownerChanged || !Objects.equals(team, existing.getTeamId())) {
            teamDirectory.requireAssignable(existing.getTenantId(), owner, team);
        }
        if (propertyChanged || ownerChanged || !Objects.equals(team, existing.getTeamId())) {
            existing.setPropertyTenantId(teamDirectory.resolveProperty(existing.getTenantId(), team, existing.getPropertyTenantId()));
        }
        existing.setOwnerUserId(owner);
        existing.setTeamId(team);
        validateAgency(existing.getTenantId(), changes.getAgencyAccountId(), existing.getAccountId());
        existing.setAgencyAccountId(changes.getAgencyAccountId());
        if (changes.getAttrs() != null) {
            if (existing.getIntakeLockedAt() != null) {
                List<String> changed = changedIntakeKeys(existing.getAttrs(), changes.getAttrs());
                if (!changed.isEmpty()) {
                    throw new IllegalStateException("The intake is locked since the deal left the inquiry stages; "
                            + "cannot change " + String.join(", ", changed));
                }
            }
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
        Long tenantId = TenantResolver.requireOrgTenantId();
        CrmOpportunity opportunity = requireOwned(id);
        CrmPipelineStage target = stageRepo.findByIdAndTenantId(request.getStageId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Stage not found: " + request.getStageId()));
        if (!target.getPipelineId().equals(opportunity.getPipelineId())) {
            throw new IllegalArgumentException("Stage does not belong to the opportunity pipeline");
        }

        List<CrmPipelineStage> stages = stageRepo.findByTenantIdAndPipelineIdOrderByDisplayOrderAsc(tenantId, opportunity.getPipelineId());
        CrmPipelineStage current = stages.stream().filter(s -> s.getId().equals(opportunity.getStageId())).findFirst()
                .orElse(null);
        checkTransition(current, target, stages);
        validateRequirements(tenantId, target.getId(), opportunity);
        if (opportunity.getIntakeLockedAt() == null && locksIntake(target, stages)) {
            opportunity.setIntakeLockedAt(OffsetDateTime.now());
        }

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
            accessContext.require(CrmPermission.OPPORTUNITY_CLOSE);
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
        if (target.getBookingStatusCode() != null && !target.getId().equals(fromStageId)) {
            bookingService.applyStageStatus(tenantId, saved.getId(), target.getBookingStatusCode(), target.getName());
        }
        return saved;
    }

    /**
     * Closing is always allowed. Between open stages a deal moves back only when its stage allows it, and forward
     * past the next open stage only when its stage allows skipping. A closed deal may reopen into any open stage.
     */
    static void checkTransition(CrmPipelineStage current, CrmPipelineStage target, List<CrmPipelineStage> stages) {
        if (current == null || current.getId().equals(target.getId())
                || target.getStageKind() != CrmPipelineStage.StageKind.OPEN
                || current.getStageKind() != CrmPipelineStage.StageKind.OPEN) {
            return;
        }
        int from = current.getDisplayOrder();
        int to = target.getDisplayOrder();
        if (to < from) {
            if (!Boolean.TRUE.equals(current.getAllowBack())) {
                throw new IllegalStateException("A deal in " + current.getName() + " cannot move back to " + target.getName());
            }
            return;
        }
        if (Boolean.TRUE.equals(current.getAllowSkip())) {
            return;
        }
        CrmPipelineStage next = stages.stream()
                .filter(s -> s.getStageKind() == CrmPipelineStage.StageKind.OPEN)
                .filter(s -> !s.getId().equals(current.getId()))
                .filter(s -> s.getDisplayOrder() >= from)
                .findFirst()
                .orElse(null);
        if (next != null && !next.getId().equals(target.getId())) {
            throw new IllegalStateException("A deal in " + current.getName() + " moves only to " + next.getName()
                    + "; it cannot skip to " + target.getName());
        }
    }

    /** The target stage locks the intake, or it is an open stage at or after one that does. */
    static boolean locksIntake(CrmPipelineStage target, List<CrmPipelineStage> stages) {
        if (Boolean.TRUE.equals(target.getLocksIntake())) {
            return true;
        }
        return target.getStageKind() == CrmPipelineStage.StageKind.OPEN && stages.stream()
                .filter(s -> s.getStageKind() == CrmPipelineStage.StageKind.OPEN)
                .anyMatch(s -> Boolean.TRUE.equals(s.getLocksIntake()) && s.getDisplayOrder() <= target.getDisplayOrder());
    }

    /** Intake fields changed by an update; they cannot change once the intake is locked. */
    static List<String> changedIntakeKeys(JsonNode before, JsonNode after) {
        return INTAKE_KEYS.stream()
                .filter(key -> !Objects.equals(intakeValue(before, key), intakeValue(after, key)))
                .toList();
    }

    private static JsonNode intakeValue(JsonNode attrs, String key) {
        if (attrs == null || !attrs.hasNonNull(key)) {
            return null;
        }
        JsonNode value = attrs.get(key);
        if (value.isTextual() && value.asText().isBlank() || value.isArray() && value.isEmpty()) {
            return null;
        }
        return value.isNumber() ? new DecimalNode(value.decimalValue().stripTrailingZeros()) : value;
    }

    public List<CrmStageTransition> transitions(Long opportunityId) {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        requireOwned(opportunityId);
        return transitionRepo.findByTenantIdAndOpportunityIdOrderByChangedAtAsc(
                TenantResolver.requireOrgTenantId(), opportunityId);
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

    private void validateAgency(Long tenantId, Long agencyAccountId, Long clientAccountId) {
        if (agencyAccountId == null) {
            return;
        }
        if (agencyAccountId.equals(clientAccountId)) {
            throw new IllegalArgumentException("Agency and client account must differ");
        }
        CrmAccount agency = accountRepo.findByIdAndTenantId(agencyAccountId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Agency account not found: " + agencyAccountId));
        if (agency.getAccountType() != CrmAccount.AccountType.AGENCY) {
            throw new IllegalArgumentException(agency.getName() + " is not an agency account");
        }
    }
}
