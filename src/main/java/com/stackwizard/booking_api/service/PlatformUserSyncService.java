package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.security.PlatformRoleMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlatformUserSyncService {

    private final AppUserRepository userRepo;

    public PlatformUserSyncService(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /**
     * Resolve or create local {@link AppUser} for a Platform user JWT.
     * Links existing users by username within the booking tenant when possible.
     */
    @Transactional
    public AppUser syncUser(UUID platformUserId,
                            String preferredUsername,
                            Long bookingTenantId,
                            Collection<String> mikosRoles) {
        if (platformUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT sub (platform user id) is required");
        }

        AppUser.Role role = PlatformRoleMapper.mapHighest(mikosRoles);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No booking role in token");
        }

        Optional<AppUser> byPlatformId = userRepo.findByPlatformUserId(platformUserId);
        if (byPlatformId.isPresent()) {
            AppUser existing = byPlatformId.get();
            boolean changed = false;
            if (role != existing.getRole()) {
                existing.setRole(role);
                changed = true;
            }
            if (role != AppUser.Role.SUPER_ADMIN
                    && bookingTenantId != null
                    && !bookingTenantId.equals(existing.getTenantId())) {
                existing.setTenantId(bookingTenantId);
                changed = true;
            }
            if (StringUtils.hasText(preferredUsername)
                    && !preferredUsername.equals(existing.getUsername())
                    && userRepo.findByUsername(preferredUsername).isEmpty()) {
                existing.setUsername(preferredUsername.trim());
                changed = true;
            }
            return changed ? userRepo.save(existing) : existing;
        }

        String username = resolveUsername(preferredUsername, platformUserId);

        if (bookingTenantId != null) {
            Optional<AppUser> byTenantUsername = userRepo.findByTenantIdAndUsername(bookingTenantId, username);
            if (byTenantUsername.isPresent()) {
                AppUser linked = byTenantUsername.get();
                linked.setPlatformUserId(platformUserId);
                linked.setRole(role);
                return userRepo.save(linked);
            }
        }

        Optional<AppUser> byUsername = userRepo.findByUsername(username);
        if (byUsername.isPresent()) {
            AppUser linked = byUsername.get();
            if (linked.getPlatformUserId() != null && !platformUserId.equals(linked.getPlatformUserId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Username already linked to another platform user: " + username);
            }
            linked.setPlatformUserId(platformUserId);
            linked.setRole(role);
            if (role != AppUser.Role.SUPER_ADMIN && bookingTenantId != null) {
                linked.setTenantId(bookingTenantId);
            }
            return userRepo.save(linked);
        }

        return userRepo.save(AppUser.builder()
                .platformUserId(platformUserId)
                .tenantId(role == AppUser.Role.SUPER_ADMIN ? null : bookingTenantId)
                .username(username)
                .passwordHash(null)
                .role(role)
                .build());
    }

    private static String resolveUsername(String preferredUsername, UUID platformUserId) {
        if (StringUtils.hasText(preferredUsername)) {
            return preferredUsername.trim();
        }
        return "platform-" + platformUserId;
    }
}
