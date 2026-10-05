package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmActivity;
import com.stackwizard.booking_api.model.CrmAttachment;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmActivityRepository;
import com.stackwizard.booking_api.repository.CrmAttachmentRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CrmActivityService {
    private final CrmActivityRepository activityRepo;
    private final CrmAttachmentRepository attachmentRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final CrmOpportunityRepository opportunityRepo;
    private final CrmLeadRepository leadRepo;
    private final MediaStorageService mediaStorageService;
    private final AuthUserAccessor authUserAccessor;
    private final CrmAccessContext accessContext;

    public CrmActivityService(CrmActivityRepository activityRepo,
                              CrmAttachmentRepository attachmentRepo,
                              CrmAccountRepository accountRepo,
                              CrmContactRepository contactRepo,
                              CrmOpportunityRepository opportunityRepo,
                              CrmLeadRepository leadRepo,
                              MediaStorageService mediaStorageService,
                              AuthUserAccessor authUserAccessor,
                              CrmAccessContext accessContext) {
        this.activityRepo = activityRepo;
        this.attachmentRepo = attachmentRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.opportunityRepo = opportunityRepo;
        this.leadRepo = leadRepo;
        this.mediaStorageService = mediaStorageService;
        this.authUserAccessor = authUserAccessor;
        this.accessContext = accessContext;
    }

    public List<CrmActivity> search(Long accountId, Long opportunityId, Long leadId, Long assignedTo, boolean openOnly) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return activityRepo.search(TenantResolver.requireTenantId(), accountId, opportunityId, leadId, assignedTo, openOnly);
    }

    public Optional<CrmActivity> findById(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return activityRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId());
    }

    @Transactional
    public CrmActivity create(CrmActivity activity) {
        accessContext.require(CrmPermission.ACTIVITY_WRITE);
        if (activity.getAccountId() == null && activity.getOpportunityId() == null && activity.getLeadId() == null) {
            throw new IllegalArgumentException("activity requires accountId, opportunityId or leadId");
        }
        Long tenantId = TenantResolver.requireTenantId();
        validateReferences(tenantId, activity);
        activity.setId(null);
        activity.setTenantId(tenantId);
        return activityRepo.save(activity);
    }

    @Transactional
    public CrmActivity update(Long id, CrmActivity changes) {
        accessContext.require(CrmPermission.ACTIVITY_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        CrmActivity existing = activityRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Activity not found: " + id));
        if (changes.getAccountId() == null && changes.getOpportunityId() == null && changes.getLeadId() == null) {
            throw new IllegalArgumentException("activity requires accountId, opportunityId or leadId");
        }
        validateReferences(tenantId, changes);
        existing.setActivityType(changes.getActivityType());
        existing.setSubject(changes.getSubject());
        existing.setBody(changes.getBody());
        existing.setAccountId(changes.getAccountId());
        existing.setContactId(changes.getContactId());
        existing.setOpportunityId(changes.getOpportunityId());
        existing.setLeadId(changes.getLeadId());
        existing.setAssignedTo(changes.getAssignedTo());
        existing.setDueAt(changes.getDueAt());
        return activityRepo.save(existing);
    }

    @Transactional
    public CrmActivity complete(Long id) {
        accessContext.require(CrmPermission.ACTIVITY_WRITE);
        CrmActivity existing = activityRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Activity not found: " + id));
        existing.setDoneAt(OffsetDateTime.now());
        return activityRepo.save(existing);
    }

    public List<CrmAttachment> listAttachments(Long accountId) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        Long tenantId = TenantResolver.requireTenantId();
        accountRepo.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        return attachmentRepo.findByTenantIdAndAccountIdOrderByCreatedAtDesc(tenantId, accountId);
    }

    @Transactional
    public CrmAttachment uploadAttachment(Long accountId, MultipartFile file) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        accountRepo.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        String storageKey = mediaStorageService.upload(
                "crm-attachments", tenantId, "account-" + accountId, file);
        CrmAttachment attachment = CrmAttachment.builder()
                .tenantId(tenantId)
                .accountId(accountId)
                .fileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "file")
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .storageKey(storageKey)
                .createdBy(authUserAccessor.currentAppUser().map(u -> u.getId()).orElse(null))
                .build();
        return attachmentRepo.save(attachment);
    }

    private void validateReferences(Long tenantId, CrmActivity activity) {
        if (activity.getAccountId() != null && accountRepo.findByIdAndTenantId(activity.getAccountId(), tenantId).isEmpty()) {
            throw new IllegalArgumentException("Account not found: " + activity.getAccountId());
        }
        if (activity.getContactId() != null && contactRepo.findByIdAndTenantId(activity.getContactId(), tenantId).isEmpty()) {
            throw new IllegalArgumentException("Contact not found: " + activity.getContactId());
        }
        if (activity.getOpportunityId() != null && opportunityRepo.findByIdAndTenantId(activity.getOpportunityId(), tenantId).isEmpty()) {
            throw new IllegalArgumentException("Opportunity not found: " + activity.getOpportunityId());
        }
        if (activity.getLeadId() != null && leadRepo.findByIdAndTenantId(activity.getLeadId(), tenantId).isEmpty()) {
            throw new IllegalArgumentException("Lead not found: " + activity.getLeadId());
        }
    }

    @Transactional
    public void deleteAttachment(Long attachmentId) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        CrmAttachment attachment = attachmentRepo.findByIdAndTenantId(attachmentId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found: " + attachmentId));
        attachmentRepo.delete(attachment);
    }
}
