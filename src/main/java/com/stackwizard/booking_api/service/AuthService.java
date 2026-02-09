package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.AuthLoginRequest;
import com.stackwizard.booking_api.dto.AuthResponse;
import com.stackwizard.booking_api.dto.AuthSignupRequest;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AppUserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(AppUserRepository userRepo,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    public AuthResponse signup(AuthSignupRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
        if (request.getRole() == null || request.getRole().isBlank()) {
            throw new IllegalArgumentException("role is required");
        }

        AppUser.Role role = AppUser.Role.valueOf(request.getRole().toUpperCase());
        if (role != AppUser.Role.SUPER_ADMIN && request.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required for non-super-admin users");
        }
        if (role == AppUser.Role.SUPER_ADMIN && request.getTenantId() != null) {
            throw new IllegalArgumentException("tenantId must be null for super-admin users");
        }

        AppUser user = AppUser.builder()
                .tenantId(request.getTenantId())
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .build();

        AppUser saved = userRepo.save(user);
        String token = jwtService.generateToken(saved);

        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .userId(saved.getId())
                .username(saved.getUsername())
                .role(saved.getRole().name())
                .tenantId(saved.getTenantId())
                .build();
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
                .role(user.getRole().name())
                .tenantId(user.getTenantId())
                .build();
    }
}
