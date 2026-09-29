package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ManagementAppUserResponse;
import com.stackwizard.booking_api.dto.ManagementUpdateEmployeeNumberRequest;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import com.stackwizard.booking_api.service.ManagementAppUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({"/api/management/users", "/booking-api/api/management/users"})
public class ManagementAppUserController {

    private final AuthUserAccessor authUserAccessor;
    private final ManagementAppUserService managementAppUserService;

    public ManagementAppUserController(AuthUserAccessor authUserAccessor,
                                        ManagementAppUserService managementAppUserService) {
        this.authUserAccessor = authUserAccessor;
        this.managementAppUserService = managementAppUserService;
    }

    /**
     * SUPER_ADMIN: pass {@code tenantId} (or rely on tenant from Platform token context).
     * Tenant ADMIN: tenant is taken from the logged-in user.
     */
    @GetMapping
    public List<ManagementAppUserResponse> list(@RequestParam(required = false) Long tenantId) {
        AppUser actor = authUserAccessor.requireAppUser();
        return managementAppUserService.listUsers(actor, tenantId);
    }

    /**
     * Update local employee number used for fiscalization. Identity/password managed on Mikos Platform.
     */
    @PatchMapping("/{userId}/employee-number")
    public ManagementAppUserResponse updateEmployeeNumber(@PathVariable("userId") Long userId,
                                                          @RequestBody ManagementUpdateEmployeeNumberRequest body) {
        AppUser actor = authUserAccessor.requireAppUser();
        return managementAppUserService.updateEmployeeNumber(actor, userId, body);
    }
}
