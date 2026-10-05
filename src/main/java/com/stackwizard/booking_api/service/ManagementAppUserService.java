package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.AuthResponse;
import com.stackwizard.booking_api.dto.ManagementAppUserResponse;
import com.stackwizard.booking_api.dto.ManagementUpdateEmployeeNumberRequest;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ManagementAppUserService {

    private final AppUserRepository userRepo;
    private final CrmAccessContext crmAccessContext;

    public ManagementAppUserService(AppUserRepository userRepo, CrmAccessContext crmAccessContext) {
        this.userRepo = userRepo;
        this.crmAccessContext = crmAccessContext;
    }

    @Transactional(readOnly = true)
    public AuthResponse me(AppUser actor) {
        Long tenantId = actor.getTenantId();
        if (tenantId == null) {
            tenantId = TenantContext.getTenantId();
        }
        return AuthResponse.builder()
                .userId(actor.getId())
                .username(actor.getUsername())
                .employeeNumber(actor.getEmployeeNumber())
                .role(actor.getRole().name())
                .roles(crmAccessContext.roleNamesForMe())
                .tenantId(tenantId)
                .build();
    }

    @Transactional(readOnly = true)
    public List<ManagementAppUserResponse> listUsers(AppUser actor, Long requestedTenantId) {
        Long tenantScope = resolveListTenantScope(actor, requestedTenantId);
        return userRepo.findByTenantIdOrderByUsernameAsc(tenantScope).stream()
                .map(ManagementAppUserService::toResponse)
                .toList();
    }

    @Transactional
    public ManagementAppUserResponse updateEmployeeNumber(AppUser actor,
                                                          Long userId,
                                                          ManagementUpdateEmployeeNumberRequest request) {
        AppUser target = userRepo.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        assertCanManage(actor, target);
        target.setEmployeeNumber(normalizeNullable(request.getEmployeeNumber()));
        return toResponse(userRepo.save(target));
    }

    private Long resolveListTenantScope(AppUser actor, Long requestedTenantId) {
        if (actor.getRole() == AppUser.Role.SUPER_ADMIN) {
            if (requestedTenantId == null) {
                Long fromContext = TenantContext.getTenantId();
                if (fromContext == null) {
                    throw new IllegalArgumentException("tenantId is required");
                }
                return fromContext;
            }
            return requestedTenantId;
        }
        if (actor.getRole() == AppUser.Role.ADMIN) {
            if (actor.getTenantId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant admin must belong to a tenant");
            }
            if (requestedTenantId != null && !requestedTenantId.equals(actor.getTenantId())) {
                throw new IllegalArgumentException("tenantId does not match your tenant");
            }
            return actor.getTenantId();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions to list users");
    }

    private void assertCanManage(AppUser actor, AppUser target) {
        if (actor.getRole() == AppUser.Role.SUPER_ADMIN) {
            return;
        }
        if (actor.getRole() == AppUser.Role.ADMIN) {
            if (actor.getTenantId() == null || target.getTenantId() == null
                    || !actor.getTenantId().equals(target.getTenantId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not in your tenant");
            }
            return;
        }
        if (actor.getId().equals(target.getId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions to update user");
    }

    private static ManagementAppUserResponse toResponse(AppUser u) {
        return ManagementAppUserResponse.builder()
                .id(u.getId())
                .tenantId(u.getTenantId())
                .username(u.getUsername())
                .role(u.getRole().name())
                .employeeNumber(u.getEmployeeNumber())
                .build();
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
