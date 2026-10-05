package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmAccountContactRole;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.repository.CrmAccountContactRoleRepository;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmOwnerScope;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class CrmAccountService {
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final CrmAccountContactRoleRepository roleRepo;
    private final CrmAccessContext accessContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrmAccountService(CrmAccountRepository accountRepo,
                             CrmContactRepository contactRepo,
                             CrmAccountContactRoleRepository roleRepo,
                             CrmAccessContext accessContext) {
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.roleRepo = roleRepo;
        this.accessContext = accessContext;
    }

    public List<CrmAccount> search(String search,
                                   CrmAccount.AccountType accountType,
                                   String segment,
                                   Long ownerUserId,
                                   Boolean active) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        Long tenantId = TenantResolver.requireTenantId();
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return accountRepo.search(
                tenantId, blankToNull(search), accountType, blankToNull(segment), ownerUserId, active,
                scope.all(), scope.own(), scope.team(), scope.currentUserId(), scope.teamUserIds(),
                org.springframework.data.domain.Pageable.unpaged()).getContent();
    }

    public Optional<CrmAccount> findById(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return accountRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .filter(a -> CrmOwnerScope.from(accessContext).allows(a.getOwnerUserId()));
    }

    @Transactional
    public CrmAccount create(CrmAccount account) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        validateParent(tenantId, null, account.getParentAccountId());
        account.setId(null);
        account.setTenantId(tenantId);
        if (account.getAccountType() == null) {
            account.setAccountType(CrmAccount.AccountType.COMPANY);
        }
        if (account.getActive() == null) {
            account.setActive(true);
        }
        if (account.getOwnerUserId() == null) {
            account.setOwnerUserId(accessContext.currentUserId());
        }
        if (account.getAttrs() == null) {
            account.setAttrs(objectMapper.createObjectNode());
        }
        return accountRepo.save(account);
    }

    @Transactional
    public CrmAccount update(Long id, CrmAccount changes) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        CrmAccount existing = requireOwned(id);
        existing.setName(changes.getName());
        existing.setLegalName(changes.getLegalName());
        if (changes.getAccountType() != null) {
            existing.setAccountType(changes.getAccountType());
        }
        existing.setSegment(changes.getSegment());
        existing.setVatId(changes.getVatId());
        validateParent(existing.getTenantId(), existing.getId(), changes.getParentAccountId());
        existing.setParentAccountId(changes.getParentAccountId());
        if (changes.getOwnerUserId() != null) {
            existing.setOwnerUserId(changes.getOwnerUserId());
        }
        existing.setTeamId(changes.getTeamId());
        existing.setEmail(changes.getEmail());
        existing.setPhone(changes.getPhone());
        existing.setWebsite(changes.getWebsite());
        existing.setAddressLine(changes.getAddressLine());
        existing.setCity(changes.getCity());
        existing.setPostalCode(changes.getPostalCode());
        existing.setCountry(changes.getCountry());
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        if (changes.getAttrs() != null) {
            existing.setAttrs(changes.getAttrs());
        }
        return accountRepo.save(existing);
    }

    @Transactional
    public void softDelete(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        CrmAccount existing = requireOwned(id);
        existing.setActive(false);
        accountRepo.save(existing);
    }

    public List<CrmContact> contactsForAccount(Long accountId) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        requireVisible(accountId);
        return contactRepo.findByTenantIdAndAccountIdOrderByLastNameAsc(TenantResolver.requireTenantId(), accountId);
    }

    @Transactional
    public CrmAccountContactRole addRole(Long accountId, Long contactId, CrmAccountContactRole role) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        requireOwned(accountId);
        contactRepo.findByIdAndTenantId(contactId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactId));
        role.setId(null);
        role.setTenantId(tenantId);
        role.setAccountId(accountId);
        role.setContactId(contactId);
        if (role.getPrimaryContact() == null) {
            role.setPrimaryContact(false);
        }
        return roleRepo.save(role);
    }

    @Transactional
    public void deleteRole(Long roleId) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        CrmAccountContactRole role = roleRepo.findByIdAndTenantId(roleId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
        requireOwned(role.getAccountId());
        roleRepo.delete(role);
    }

    private CrmAccount requireOwned(Long id) {
        return findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private void requireVisible(Long id) {
        requireOwned(id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateParent(Long tenantId, Long accountId, Long parentAccountId) {
        if (parentAccountId == null) {
            return;
        }
        if (parentAccountId.equals(accountId)) {
            throw new IllegalArgumentException("Account cannot be its own parent");
        }
        if (accountRepo.findByIdAndTenantId(parentAccountId, tenantId).isEmpty()) {
            throw new IllegalArgumentException("Parent account not found: " + parentAccountId);
        }
    }
}
