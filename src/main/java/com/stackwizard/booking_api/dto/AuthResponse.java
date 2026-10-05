package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AuthResponse {
    private Long userId;
    private String username;
    private String employeeNumber;
    private String role;
    private List<String> roles;
    private Long tenantId;
}
