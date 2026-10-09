package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.TenantOrganizationDto;
import com.stackwizard.booking_api.dto.TenantPropertyDto;
import com.stackwizard.booking_api.service.TenantPropertyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tenant")
public class TenantPropertyController {
    private final TenantPropertyService service;

    public TenantPropertyController(TenantPropertyService service) {
        this.service = service;
    }

    /** Organizations of the caller; works before an organization is onboarded. */
    @GetMapping("/organizations")
    public List<TenantOrganizationDto> organizations(@AuthenticationPrincipal Jwt jwt) {
        return service.listOrganizations(jwt);
    }

    /** Hotels of the current organization that the caller may switch between. */
    @GetMapping("/properties")
    public List<TenantPropertyDto> accessible(@AuthenticationPrincipal Jwt jwt) {
        return service.listAccessible(jwt);
    }
}
