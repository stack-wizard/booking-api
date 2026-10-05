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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrmLeadService(CrmLeadRepository leadRepo,
                          CrmAccountRepository accountRepo,
                          CrmContactRepository contactRepo,
                          CrmPipelineRepository pipelineRepo,
                          CrmPipelineStageRepository stageRepo,
                          CrmOpportunityRepository opportunityRepo,
                          CrmStageTransitionRepository transitionRepo,
                          CrmAccessContext accessContext) {
        this.leadRepo = leadRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.pipelineRepo = pipelineRepo;
        this.stageRepo = stageRepo;
        this.opportunityRepo = opportunityRepo;
        this.transitionRepo = transitionRepo;
        this.accessContext = accessContext;
    }

    public List<CrmLead> findAll() {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return leadRepo.findScoped(
                TenantResolver.requireTenantId(),
                scope.all(), scope.own(), scope.team(), scope.currentUserId(), scope.teamUserIds());
    }

    public Optional<CrmLead> findById(Long id) {
        accessContext.require(CrmPermission.OPPORTUNITY_READ);
        return leadRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .filter(l -> CrmOwnerScope.from(accessContext).allows(l.getOwnerUserId()));
    }

    @Transactional
    public CrmLead create(CrmLead lead) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        lead.setId(null);
        lead.setTenantId(TenantResolver.requireTenantId());
        if (lead.getStatus() == null) {
            lead.setStatus(CrmLead.Status.NEW);
        }
        if (lead.getOwnerUserId() == null) {
            lead.setOwnerUserId(accessContext.currentUserId());
        }
        if (lead.getAttrs() == null) {
            lead.setAttrs(objectMapper.createObjectNode());
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
        if (changes.getOwnerUserId() != null) {
            existing.setOwnerUserId(changes.getOwnerUserId());
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
        Long tenantId = TenantResolver.requireTenantId();
        CrmLead lead = requireOwned(id);
        if (lead.getStatus() == CrmLead.Status.CONVERTED) {
            throw new IllegalStateException("Lead already converted");
        }
        if (lead.getStatus() == CrmLead.Status.DISQUALIFIED) {
            throw new IllegalStateException("Disqualified lead cannot be converted");
        }

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
                    .ownerUserId(lead.getOwnerUserId() != null ? lead.getOwnerUserId() : accessContext.currentUserId())
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
                .ownerUserId(lead.getOwnerUserId() != null ? lead.getOwnerUserId() : accessContext.currentUserId())
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

    private CrmLead requireOwned(Long id) {
        return findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
    }
}
