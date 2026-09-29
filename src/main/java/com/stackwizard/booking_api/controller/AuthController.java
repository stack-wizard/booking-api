package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.AuthResponse;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import com.stackwizard.booking_api.service.ManagementAppUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/auth", "/booking-api/api/auth"})
public class AuthController {
    private final AuthUserAccessor authUserAccessor;
    private final ManagementAppUserService managementAppUserService;

    public AuthController(AuthUserAccessor authUserAccessor,
                          ManagementAppUserService managementAppUserService) {
        this.authUserAccessor = authUserAccessor;
        this.managementAppUserService = managementAppUserService;
    }

    @GetMapping("/me")
    public AuthResponse me() {
        AppUser actor = authUserAccessor.requireAppUser();
        return managementAppUserService.me(actor);
    }
}
