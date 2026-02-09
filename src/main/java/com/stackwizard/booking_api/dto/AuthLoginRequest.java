package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class AuthLoginRequest {
    private String username;
    private String password;
}
