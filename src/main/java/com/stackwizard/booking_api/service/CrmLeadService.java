package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.CrmLeadConvertRequest;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.model.CrmOpportunity;
import com.stackwizard.booking_api.model.CrmPipeline;
import com.stackwizard.booking_api.model.CrmPipelineStage;
import com.stackwizard.booking_api.model.CrmStageTransition;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmPipelineRepository;
import com.stackwizard.booking_api.repository.CrmPipelineStageRepository;
import com.stackwizard.booking_api.repository.CrmStageTransitionRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmOwnerScope;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CrmLeadService {
    private final CrmLeadRepository leadRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final CrmPipelineRepository pipelineRepo;
    private final CrmPipelineStageRepository stageRepo;
    private final CrmOpportunityRepository opportunityRepo;
    private final CrmStageTransitionRepository transitionRepo;
    private final CrmAccessContext accessContext;
    private final CrmTeamDirectory teamDirectory;
    private final CrmSegmentService segmentService;
    private final CrmLeadAssignmentService assignmentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrmLeadService(CrmLeadRepository leadRepo,
                          CrmAccountRepository accountRepo,
                          CrmContactRepository contactRepo,
                          CrmPipelineRepository pipelineRepo,
                          CrmPipelineStageRepository stageRepo,
                          CrmOpportunityRepository opportunityRepo,
                          CrmStageTransitionRepository transitionRepo,
                          CrmAccessContext accessContext,
                          CrmTeamDirectory teamDirectory,
                          CrmSegmentService segmentService,
                          CrmLeadAssignmentService assignmentService) {
        this.leadRepo = leadRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.pipelineRepo = pipelineRepo;
        this.stageRepo = stageRepo;
        this.opportunityRepo = opportunityRepo;
        this.transitionRepo = transitionRepo;
        this.accessContext = accessContext;
        this.teamDirectory = teamDirectory;
        this.segmentService = segmentService;
        this.assignmentService = assignmentService;
    }

    public List<CrmLead> findAll() {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return leadRepo.findScoped(
                TenantResolver.requireOrgTenantId(),
                scope.all(), scope.own(), scope.team(), scope.currentUserId(), scope.teamUserIds(), scope.teamIds());
    }

    public Optional<CrmLead> findById(Long id) {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        return leadRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .filter(l -> CrmOwnerScope.from(accessContext).allows(l.getOwnerUserId(), l.getTeamId()));
    }

    @Transactional
    public CrmLead create(CrmLead lead) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        Long tenantId = TenantResolver.requireOrgTenantId();
        lead.setId(null);
        lead.setTenantId(tenantId);
        if (lead.getStatus() == null) {
            lead.setStatus(CrmLead.Status.NEW);
        }
        if (lead.getAttrs() == null) {
            lead.setAttrs(objectMapper.createObjectNode());
        }
        lead.setSegment(segmentService.normalize(tenantId, lead.getSegment()));
        lead.setCountry(normalizeCountry(lead.getCountry()));
        lead.setAssignmentRuleId(null);
        if (lead.getOwnerUserId() == null && lead.getTeamId() == null) {
            assignmentService.assign(lead, accessContext.currentUserId());
        } else {
            lead.setTeamId(teamDirectory.resolveTeam(tenantId, lead.getOwnerUserId(), lead.getTeamId(), null));
            teamDirectory.requireAssignable(tenantId, lead.getOwnerUserId(), lead.getTeamId());
            lead.setAssignedAt(OffsetDateTime.now());
        }
        return leadRepo.save(lead);
    }

    @Transactional
    public CrmLead update(Long id, CrmLead changes) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        CrmLead existing = requireOwned(id);
        if (changes.getStatus() == CrmLead.Status.CONVERTED && existing.getStatus() != CrmLead.Status.CONVERTED) {
            throw new IllegalArgumentException("Use the convert endpoint to convert a lead");
        }
        if (existing.getStatus() == CrmLead.Status.CONVERTED
                && changes.getStatus() != null && changes.getStatus() != CrmLead.Status.CONVERTED) {
            throw new IllegalStateException("Converted lead status cannot be changed");
        }
        existing.setCompanyName(changes.getCompanyName());
        existing.setFirstName(changes.getFirstName());
        existing.setLastName(changes.getLastName());
        existing.setEmail(changes.getEmail());
        existing.setPhone(changes.getPhone());
        existing.setSource(changes.getSource());
        existing.setDescription(changes.getDescription());
        if (changes.getStatus() != null) {
            existing.setStatus(changes.getStatus());
        }
        if (changes.getSegment() != null) {
            existing.setSegment(segmentService.normalize(existing.getTenantId(), changes.getSegment()));
        }
        if (changes.getCountry() != null) {
            existing.setCountry(normalizeCountry(changes.getCountry()));
        }
        if (changes.getOwnerUserId() != null || changes.getTeamId() != null) {
            applyAssignment(existing, changes.getOwnerUserId() != null ? changes.getOwnerUserId() : existing.getOwnerUserId(),
                    changes.getTeamId());
        }
        if (changes.getAttrs() != null) {
            existing.setAttrs(changes.getAttrs());
        }
        existing.setDisqualifyReasonId(changes.getDisqualifyReasonId());
        return leadRepo.save(existing);
    }

    @Transactional
    public CrmLead convert(Long id, CrmLeadConvertRequest request) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        Long tenantId = TenantResolver.requireOrgTenantId();
        CrmLead lead = requireOwned(id);
        if (lead.getStatus() == CrmLead.Status.CONVERTED) {
            throw new IllegalStateException("Lead already converted");
        }
        if (lead.getStatus() == CrmLead.Status.DISQUALIFIED) {
            throw new IllegalStateException("Disqualified lead cannot be converted");
        }

        Long owner = lead.getOwnerUserId() != null ? lead.getOwnerUserId() : accessContext.currentUserId();
        Long team = lead.getTeamId() != null ? lead.getTeamId() : teamDirectory.primaryTeamOf(tenantId, owner).orElse(null);
        CrmAccount account;
        if (request != null && request.getAccountId() != null) {
            account = accountRepo.findByIdAndTenantId(request.getAccountId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        } else {
            String name = lead.getCompanyName();
            if (name == null || name.isBlank()) {
                name = ((lead.getFirstName() == null ? "" : lead.getFirstName()) + " "
                        + (lead.getLastName() == null ? "" : lead.getLastName())).trim();
            }
            if (name.isBlank()) {
                throw new IllegalArgumentException("companyName or contact name is required to create account");
            }
            account = accountRepo.save(CrmAccount.builder()
                    .tenantId(tenantId)
                    .name(name)
                    .accountType(CrmAccount.AccountType.COMPANY)
                    .ownerUserId(owner)
                    .teamId(team)
                    .segment(lead.getSegment())
                    .country(lead.getCountry())
                    .email(lead.getEmail())
                    .phone(lead.getPhone())
                    .active(true)
                    .attrs(objectMapper.createObjectNode())
                    .build());
        }

        CrmContact contact;
        if (request != null && request.getContactId() != null) {
            contact = contactRepo.findByIdAndTenantId(request.getContactId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Contact not found"));
        } else {
            String first = lead.getFirstName() != null && !lead.getFirstName().isBlank() ? lead.getFirstName() : "Unknown";
            String last = lead.getLastName() != null && !lead.getLastName().isBlank() ? lead.getLastName() : "Contact";
            contact = contactRepo.save(CrmContact.builder()
                    .tenantId(tenantId)
                    .accountId(account.getId())
                    .firstName(first)
                    .lastName(last)
                    .email(lead.getEmail())
                    .phone(lead.getPhone())
                    .active(true)
                    .attrs(objectMapper.createObjectNode())
                    .build());
        }

        CrmPipeline pipeline;
        if (request != null && request.getPipelineId() != null) {
            pipeline = pipelineRepo.findByIdAndTenantId(request.getPipelineId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Pipeline not found"));
        } else {
            pipeline = pipelineRepo.findByTenantIdAndIsDefaultTrue(tenantId)
                    .orElseThrow(() -> new IllegalStateException("No default pipeline configured"));
        }
        CrmPipelineStage firstStage = stageRepo.findFirstByTenantIdAndPipelineIdOrderByDisplayOrderAsc(tenantId, pipeline.getId())
                .orElseThrow(() -> new IllegalStateException("Pipeline has no stages"));

        String oppName = request != null && request.getOpportunityName() != null && !request.getOpportunityName().isBlank()
                ? request.getOpportunityName()
                : (account.getName() + " opportunity");

        CrmOpportunity opportunity = opportunityRepo.save(CrmOpportunity.builder()
                .tenantId(tenantId)
                .name(oppName)
                .accountId(account.getId())
                .primaryContactId(contact.getId())
                .pipelineId(pipeline.getId())
                .stageId(firstStage.getId())
                .ownerUserId(owner)
                .teamId(team)
                .currency("EUR")
                .source(lead.getSource())
                .status(CrmOpportunity.Status.OPEN)
                .attrs(lead.getAttrs() != null ? lead.getAttrs().deepCopy() : objectMapper.createObjectNode())
                .build());

        transitionRepo.save(CrmStageTransition.builder()
                .tenantId(tenantId)
                .opportunityId(opportunity.getId())
                .fromStageId(null)
                .toStageId(firstStage.getId())
                .changedBy(accessContext.currentUserId())
                .note("converted from lead " + lead.getId())
                .build());

        lead.setStatus(CrmLead.Status.CONVERTED);
        lead.setConvertedAccountId(account.getId());
        lead.setConvertedContactId(contact.getId());
        lead.setConvertedOpportunityId(opportunity.getId());
        lead.setConvertedAt(OffsetDateTime.now());
        return leadRepo.save(lead);
    }

    /** Runs the assignment rules again, e.g. for a lead waiting in a team queue. */
    @Transactional
    public CrmLead autoAssign(Long id) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        CrmLead lead = requireOwned(id);
        requireOpen(lead);
        CrmLeadAssignmentService.Decision decision = assignmentService.assign(lead, accessContext.currentUserId());
        teamDirectory.requireAssignable(lead.getTenantId(), decision.ownerUserId(), decision.teamId());
        return leadRepo.save(lead);
    }

    /** Hands the lead to a rep and/or team; a team without owner puts it in that team's queue. */
    @Transactional
    public CrmLead assign(Long id, Long ownerUserId, Long teamId) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        CrmLead lead = requireOwned(id);
        requireOpen(lead);
        if (ownerUserId == null && teamId == null) {
            throw new IllegalArgumentException("ownerUserId or teamId is required");
        }
        applyAssignment(lead, ownerUserId, teamId);
        return leadRepo.save(lead);
    }

    private void applyAssignment(CrmLead lead, Long ownerUserId, Long teamId) {
        Long team = teamDirectory.resolveTeam(lead.getTenantId(), ownerUserId, teamId, lead.getTeamId());
        if (!java.util.Objects.equals(ownerUserId, lead.getOwnerUserId()) || !java.util.Objects.equals(team, lead.getTeamId())
                || lead.getAssignedAt() == null) {
            teamDirectory.requireAssignable(lead.getTenantId(), ownerUserId, team);
            lead.setAssignedAt(OffsetDateTime.now());
        }
        lead.setOwnerUserId(ownerUserId);
        lead.setTeamId(team);
    }

    private static void requireOpen(CrmLead lead) {
        if (lead.getStatus() == CrmLead.Status.CONVERTED || lead.getStatus() == CrmLead.Status.DISQUALIFIED) {
            throw new IllegalStateException("Lead is " + lead.getStatus() + " and cannot be reassigned");
        }
    }

    private static String normalizeCountry(String country) {
        if (country == null || country.isBlank()) {
            return null;
        }
        String value = country.trim().toUpperCase();
        if (value.length() != 2) {
            throw new IllegalArgumentException("country must be an ISO 3166-1 alpha-2 code");
        }
        return value;
    }

    private CrmLead requireOwned(Long id) {
        return findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
    }
}
