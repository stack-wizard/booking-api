package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CrmContactService {
    private final CrmContactRepository repo;
    private final CrmAccountRepository accountRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CrmAccessContext accessContext;

    public CrmContactService(CrmContactRepository repo, CrmAccountRepository accountRepo, CrmAccessContext accessContext) {
        this.repo = repo;
        this.accountRepo = accountRepo;
        this.accessContext = accessContext;
    }

    public List<CrmContact> findAll() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return repo.findByTenantIdOrderByLastNameAsc(TenantResolver.requireOrgTenantId());
    }

    public Optional<CrmContact> findById(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return repo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId());
    }

    @Transactional
    public CrmContact create(CrmContact contact) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        Long tenantId = TenantResolver.requireOrgTenantId();
        validateAccount(tenantId, contact.getAccountId());
        contact.setId(null);
        contact.setTenantId(tenantId);
        if (contact.getActive() == null) {
            contact.setActive(true);
        }
        if (contact.getAttrs() == null) {
            contact.setAttrs(objectMapper.createObjectNode());
        }
        return repo.save(contact);
    }

    @Transactional
    public CrmContact update(Long id, CrmContact changes) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        CrmContact existing = repo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + id));
        validateAccount(existing.getTenantId(), changes.getAccountId());
        existing.setAccountId(changes.getAccountId());
        existing.setFirstName(changes.getFirstName());
        existing.setLastName(changes.getLastName());
        existing.setEmail(changes.getEmail());
        existing.setPhone(changes.getPhone());
        existing.setJobTitle(changes.getJobTitle());
        existing.setPlatformUserId(changes.getPlatformUserId());
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        if (changes.getAttrs() != null) {
            existing.setAttrs(changes.getAttrs());
        }
        return repo.save(existing);
    }

    @Transactional
    public void softDelete(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        CrmContact existing = repo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + id));
        existing.setActive(false);
        repo.save(existing);
    }

    private void validateAccount(Long tenantId, Long accountId) {
        if (accountId != null && accountRepo.findByIdAndTenantId(accountId, tenantId).isEmpty()) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
    }
}
