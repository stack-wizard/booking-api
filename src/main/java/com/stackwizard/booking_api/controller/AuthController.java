package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.AuthLoginRequest;
import com.stackwizard.booking_api.dto.AuthResponse;
import com.stackwizard.booking_api.dto.AuthSignupRequest;
import com.stackwizard.booking_api.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/signup")
    public AuthResponse signup(@RequestBody AuthSignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthLoginRequest request) {
        return authService.login(request);
    }
}
