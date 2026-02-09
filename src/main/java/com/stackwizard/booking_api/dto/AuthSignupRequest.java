package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class AuthSignupRequest {
    private Long tenantId;
    private String username;
    private String password;
    private String role;
}
