package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ManagementAppUserResponse;
import com.stackwizard.booking_api.dto.ManagementCreateAppUserRequest;
import com.stackwizard.booking_api.dto.ManagementResetUserPasswordRequest;
import com.stackwizard.booking_api.dto.ManagementUpdateOwnPasswordRequest;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import com.stackwizard.booking_api.service.ManagementAppUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/management/users")
public class ManagementAppUserController {

    private final AuthUserAccessor authUserAccessor;
    private final ManagementAppUserService managementAppUserService;

    public ManagementAppUserController(AuthUserAccessor authUserAccessor,
                                        ManagementAppUserService managementAppUserService) {
        this.authUserAccessor = authUserAccessor;
        this.managementAppUserService = managementAppUserService;
    }

    /**
     * SUPER_ADMIN: pass {@code tenantId}. Tenant ADMIN: tenant is taken from the logged-in user
     * ({@code tenantId} query must match or be omitted).
     */
    @GetMapping
    public List<ManagementAppUserResponse> list(@RequestParam(required = false) Long tenantId) {
        AppUser actor = authUserAccessor.requireAppUser();
        return managementAppUserService.listUsers(actor, tenantId);
    }

    /**
     * SUPER_ADMIN: creates tenant users (roles ADMIN, STAFF, CASHIER); body {@code tenantId} required.
     * Tenant ADMIN: creates ADMIN or CASHIER for the same tenant as the JWT user.
     */
    @PostMapping
    public ManagementAppUserResponse create(@RequestBody ManagementCreateAppUserRequest body) {
        AppUser actor = authUserAccessor.requireAppUser();
        return managementAppUserService.createUser(actor, body);
    }

    /** Authenticated user changes own password (must supply current password). */
    @PatchMapping("/me/password")
    public void updateOwnPassword(@RequestBody ManagementUpdateOwnPasswordRequest body) {
        AppUser actor = authUserAccessor.requireAppUser();
        managementAppUserService.updateOwnPassword(actor, body);
    }

    /**
     * SUPER_ADMIN: reset password for any user (except another super admin, unless self).
     * Tenant ADMIN: reset password for ADMIN or CASHIER in the same tenant.
     */
    @PatchMapping("/{userId}/password")
    public void resetUserPassword(@PathVariable("userId") Long userId,
                                  @RequestBody ManagementResetUserPasswordRequest body) {
        AppUser actor = authUserAccessor.requireAppUser();
        managementAppUserService.resetUserPassword(actor, userId, body);
    }
}
