package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
    private Long userId;
    private String username;
    private String employeeNumber;
    private String role;
    private Long tenantId;
}
