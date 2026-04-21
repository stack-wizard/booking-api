package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class ManagementResetUserPasswordRequest {
    private String newPassword;
}
