package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ManagementAppUserResponse {
    Long id;
    Long tenantId;
    String username;
    String role;
    String employeeNumber;
}
