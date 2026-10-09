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
import java.util.Objects;
import java.util.Optional;

@Service
public class CrmAccountService {
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final CrmAccountContactRoleRepository roleRepo;
    private final CrmAccessContext accessContext;
    private final CrmTeamDirectory teamDirectory;
    private final CrmSegmentService segmentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrmAccountService(CrmAccountRepository accountRepo,
                             CrmContactRepository contactRepo,
                             CrmAccountContactRoleRepository roleRepo,
                             CrmAccessContext accessContext,
                             CrmTeamDirectory teamDirectory,
                             CrmSegmentService segmentService) {
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.roleRepo = roleRepo;
        this.accessContext = accessContext;
        this.teamDirectory = teamDirectory;
        this.segmentService = segmentService;
    }

    public List<CrmAccount> search(String search,
                                   CrmAccount.AccountType accountType,
                                   String segment,
                                   Long ownerUserId,
                                   Boolean active) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        Long tenantId = TenantResolver.requireOrgTenantId();
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return accountRepo.search(
                tenantId, blankToNull(search), accountType, blankToNull(segment), ownerUserId, active,
                scope.all(), scope.own(), scope.team(), scope.currentUserId(), scope.teamUserIds(), scope.teamIds(),
                org.springframework.data.domain.Pageable.unpaged()).getContent();
    }

    public Optional<CrmAccount> findById(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return accountRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .filter(a -> CrmOwnerScope.from(accessContext).allows(a.getOwnerUserId(), a.getTeamId()));
    }

    @Transactional
    public CrmAccount create(CrmAccount account) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        Long tenantId = TenantResolver.requireOrgTenantId();
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
        account.setSegment(segmentService.normalize(tenantId, account.getSegment()));
        account.setTeamId(teamDirectory.resolveTeam(tenantId, account.getOwnerUserId(), account.getTeamId(),
                segmentService.defaultTeam(tenantId, account.getSegment())));
        teamDirectory.requireAssignable(tenantId, account.getOwnerUserId(), account.getTeamId());
        if (account.getAttrs() == null) {
            account.setAttrs(objectMapper.createObjectNode());
        }
        return accountRepo.save(account);
    }

    @Transactional
    public CrmAccount update(Long id, CrmAccount changes) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        CrmAccount existing = requireOwned(id);
        Long tenantId = existing.getTenantId();
        existing.setName(changes.getName());
        existing.setLegalName(changes.getLegalName());
        if (changes.getAccountType() != null) {
            existing.setAccountType(changes.getAccountType());
        }
        existing.setSegment(segmentService.normalize(tenantId, changes.getSegment()));
        existing.setVatId(changes.getVatId());
        validateParent(tenantId, existing.getId(), changes.getParentAccountId());
        existing.setParentAccountId(changes.getParentAccountId());
        applyOwnership(tenantId, existing, changes.getOwnerUserId(), changes.getTeamId());
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
        return contactRepo.findByTenantIdAndAccountIdOrderByLastNameAsc(TenantResolver.requireOrgTenantId(), accountId);
    }

    @Transactional
    public CrmAccountContactRole addRole(Long accountId, Long contactId, CrmAccountContactRole role) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        Long tenantId = TenantResolver.requireOrgTenantId();
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
        CrmAccountContactRole role = roleRepo.findByIdAndTenantId(roleId, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
        requireOwned(role.getAccountId());
        roleRepo.delete(role);
    }

    public CrmAccount requireOwned(Long id) {
        return findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private void applyOwnership(Long tenantId, CrmAccount existing, Long ownerUserId, Long teamId) {
        Long owner = ownerUserId != null ? ownerUserId : existing.getOwnerUserId();
        boolean ownerChanged = !Objects.equals(owner, existing.getOwnerUserId());
        Long team = teamId != null ? teamId
                : ownerChanged ? teamDirectory.resolveTeam(tenantId, owner, null, existing.getTeamId())
                : existing.getTeamId();
        if (ownerChanged || !Objects.equals(team, existing.getTeamId())) {
            teamDirectory.requireAssignable(tenantId, owner, team);
        }
        existing.setOwnerUserId(owner);
        existing.setTeamId(team);
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
        Long cursor = parentAccountId;
        for (int depth = 0; cursor != null && depth < 50; depth++) {
            if (cursor.equals(accountId)) {
                throw new IllegalArgumentException("Parent account would create a cycle");
            }
            cursor = accountRepo.findByIdAndTenantId(cursor, tenantId).map(CrmAccount::getParentAccountId).orElse(null);
        }
    }
}
