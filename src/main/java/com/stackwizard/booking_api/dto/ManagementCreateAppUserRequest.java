package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class ManagementCreateAppUserRequest {
    /** Required when caller is SUPER_ADMIN; ignored for tenant ADMIN (tenant comes from token). */
    private Long tenantId;
    private String username;
    private String password;
    private String role;
    private String employeeNumber;
}
