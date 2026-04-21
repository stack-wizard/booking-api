package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.AuthLoginRequest;
import com.stackwizard.booking_api.dto.AuthResponse;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AppUserRepository userRepo;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(AppUserRepository userRepo,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService) {
        this.userRepo = userRepo;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    public AuthResponse login(AuthLoginRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("password is required");
        }

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        AppUser user = userRepo.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        String token = jwtService.generateToken(user);

        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(user.getId())
                .username(user.getUsername())
                .employeeNumber(user.getEmployeeNumber())
                .role(user.getRole().name())
                .tenantId(user.getTenantId())
                .build();
    }
}
