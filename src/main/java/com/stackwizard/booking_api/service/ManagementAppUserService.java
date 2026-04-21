package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ManagementAppUserResponse;
import com.stackwizard.booking_api.dto.ManagementCreateAppUserRequest;
import com.stackwizard.booking_api.dto.ManagementResetUserPasswordRequest;
import com.stackwizard.booking_api.dto.ManagementUpdateOwnPasswordRequest;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.repository.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class ManagementAppUserService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final Set<AppUser.Role> SUPER_ADMIN_CREATABLE = EnumSet.of(
            AppUser.Role.ADMIN,
            AppUser.Role.STAFF,
            AppUser.Role.CASHIER
    );

    private static final Set<AppUser.Role> TENANT_ADMIN_CREATABLE = EnumSet.of(
            AppUser.Role.ADMIN,
            AppUser.Role.CASHIER
    );

    private final AppUserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    public ManagementAppUserService(AppUserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<ManagementAppUserResponse> listUsers(AppUser actor, Long requestedTenantId) {
        Long tenantScope = resolveListTenantScope(actor, requestedTenantId);
        return userRepo.findByTenantIdOrderByUsernameAsc(tenantScope).stream()
                .map(ManagementAppUserService::toResponse)
                .toList();
    }

    private Long resolveListTenantScope(AppUser actor, Long requestedTenantId) {
        if (actor.getRole() == AppUser.Role.SUPER_ADMIN) {
            if (requestedTenantId == null) {
                throw new IllegalArgumentException("tenantId is required");
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

    @Transactional
    public ManagementAppUserResponse createUser(AppUser actor, ManagementCreateAppUserRequest request) {
        String username = normalizeUsername(request.getUsername());
        String password = request.getPassword();
        validatePassword(password);
        AppUser.Role newRole = parseRole(request.getRole());

        if (newRole == AppUser.Role.SUPER_ADMIN) {
            throw new IllegalArgumentException("Cannot create SUPER_ADMIN users via this API");
        }

        if (userRepo.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }

        Long tenantId;
        Set<AppUser.Role> allowedRoles;

        if (actor.getRole() == AppUser.Role.SUPER_ADMIN) {
            if (request.getTenantId() == null) {
                throw new IllegalArgumentException("tenantId is required when creating users as super admin");
            }
            tenantId = request.getTenantId();
            allowedRoles = SUPER_ADMIN_CREATABLE;
        } else if (actor.getRole() == AppUser.Role.ADMIN) {
            if (actor.getTenantId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant admin must belong to a tenant");
            }
            tenantId = actor.getTenantId();
            allowedRoles = TENANT_ADMIN_CREATABLE;
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions to create users");
        }

        if (!allowedRoles.contains(newRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Role " + newRole + " cannot be assigned by " + actor.getRole());
        }

        AppUser created = userRepo.save(AppUser.builder()
                .tenantId(tenantId)
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .employeeNumber(normalizeNullable(request.getEmployeeNumber()))
                .role(newRole)
                .build());

        return toResponse(created);
    }

    @Transactional
    public void updateOwnPassword(AppUser actor, ManagementUpdateOwnPasswordRequest request) {
        if (!StringUtils.hasText(request.getCurrentPassword())) {
            throw new IllegalArgumentException("currentPassword is required");
        }
        validatePassword(request.getNewPassword());
        AppUser u = userRepo.findById(actor.getId())
                .orElseThrow(() -> new IllegalStateException("User not found"));
        if (!passwordEncoder.matches(request.getCurrentPassword(), u.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        u.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepo.save(u);
    }

    @Transactional
    public void resetUserPassword(AppUser actor, Long targetUserId, ManagementResetUserPasswordRequest request) {
        validatePassword(request.getNewPassword());
        AppUser target = userRepo.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (actor.getRole() == AppUser.Role.SUPER_ADMIN) {
            if (target.getRole() == AppUser.Role.SUPER_ADMIN && !actor.getId().equals(target.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot reset another super admin password");
            }
        } else if (actor.getRole() == AppUser.Role.ADMIN) {
            if (actor.getTenantId() == null || target.getTenantId() == null
                    || !actor.getTenantId().equals(target.getTenantId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not in your tenant");
            }
            if (target.getRole() == AppUser.Role.SUPER_ADMIN) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot reset super admin password");
            }
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions to reset passwords");
        }

        target.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepo.save(target);
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

    private static String normalizeUsername(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        return raw.trim();
    }

    private static void validatePassword(String password) {
        if (!StringUtils.hasText(password) || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static AppUser.Role parseRole(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("role is required");
        }
        try {
            return AppUser.Role.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role: " + raw);
        }
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
