package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CrmTeamDtos;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmAccountRelation;
import com.stackwizard.booking_api.repository.CrmAccountRelationRepository;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Account graph: the parent/child hierarchy (group → hotel company) plus typed links such as an agency
 * booking for a client or a partner company.
 */
@Service
public class CrmAccountRelationService {
    private final CrmAccountRelationRepository relationRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmAccountService accountService;
    private final CrmAccessContext accessContext;

    public CrmAccountRelationService(CrmAccountRelationRepository relationRepo,
                                     CrmAccountRepository accountRepo,
                                     CrmAccountService accountService,
                                     CrmAccessContext accessContext) {
        this.relationRepo = relationRepo;
        this.accountRepo = accountRepo;
        this.accountService = accountService;
        this.accessContext = accessContext;
    }

    @Transactional(readOnly = true)
    public List<CrmTeamDtos.RelationView> forAccount(Long accountId) {
        CrmAccount account = accountService.requireOwned(accountId);
        Long tenantId = account.getTenantId();
        List<CrmTeamDtos.RelationView> views = new ArrayList<>();
        if (account.getParentAccountId() != null) {
            accountRepo.findByIdAndTenantId(account.getParentAccountId(), tenantId).ifPresent(parent ->
                    views.add(new CrmTeamDtos.RelationView(null, "PARENT", false, parent.getId(), parent.getName(),
                            parent.getAccountType(), null, null)));
        }
        for (CrmAccount child : accountRepo.findByTenantIdAndParentAccountIdOrderByNameAsc(tenantId, accountId)) {
            views.add(new CrmTeamDtos.RelationView(null, "CHILD", true, child.getId(), child.getName(),
                    child.getAccountType(), null, null));
        }
        List<CrmAccountRelation> relations = relationRepo.findForAccount(tenantId, accountId);
        List<Long> otherIds = relations.stream()
                .map(r -> r.getFromAccountId().equals(accountId) ? r.getToAccountId() : r.getFromAccountId())
                .distinct().toList();
        Map<Long, CrmAccount> others = accountRepo.findAllById(otherIds).stream()
                .filter(a -> a.getTenantId().equals(tenantId))
                .collect(Collectors.toMap(CrmAccount::getId, Function.identity()));
        for (CrmAccountRelation r : relations) {
            boolean outgoing = r.getFromAccountId().equals(accountId);
            CrmAccount other = others.get(outgoing ? r.getToAccountId() : r.getFromAccountId());
            if (other == null) {
                continue;
            }
            views.add(new CrmTeamDtos.RelationView(r.getId(), r.getRelationType().name(), outgoing, other.getId(), other.getName(),
                    other.getAccountType(), r.getNote(), r.getCreatedAt()));
        }
        return views;
    }

    @Transactional
    public CrmAccountRelation create(Long fromAccountId, CrmTeamDtos.RelationRequest request) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        if (request == null || request.toAccountId() == null || request.relationType() == null) {
            throw new IllegalArgumentException("toAccountId and relationType are required");
        }
        CrmAccount from = accountService.requireOwned(fromAccountId);
        CrmAccount to = accountRepo.findByIdAndTenantId(request.toAccountId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.toAccountId()));
        if (from.getId().equals(to.getId())) {
            throw new IllegalArgumentException("An account cannot be related to itself");
        }
        if (request.relationType() == CrmAccountRelation.Type.AGENCY_FOR && from.getAccountType() != CrmAccount.AccountType.AGENCY) {
            throw new IllegalArgumentException(from.getName() + " is not an agency account");
        }
        boolean exists = relationRepo.existsByTenantIdAndFromAccountIdAndToAccountIdAndRelationType(
                tenantId, from.getId(), to.getId(), request.relationType())
                || (request.relationType() == CrmAccountRelation.Type.PARTNER
                && relationRepo.existsByTenantIdAndFromAccountIdAndToAccountIdAndRelationType(
                tenantId, to.getId(), from.getId(), request.relationType()));
        if (exists) {
            throw new IllegalStateException("Relation already exists");
        }
        return relationRepo.save(CrmAccountRelation.builder()
                .tenantId(tenantId)
                .fromAccountId(from.getId())
                .toAccountId(to.getId())
                .relationType(request.relationType())
                .note(request.note())
                .createdBy(accessContext.currentUserId())
                .build());
    }

    @Transactional
    public void delete(Long relationId) {
        accessContext.require(CrmPermission.ACCOUNT_WRITE);
        CrmAccountRelation relation = relationRepo.findByIdAndTenantId(relationId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Relation not found: " + relationId));
        accountService.requireOwned(relation.getFromAccountId());
        relationRepo.delete(relation);
    }
}
