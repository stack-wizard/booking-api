package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.repository.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Component
public class AuthUserAccessor {

    private final AppUserRepository userRepo;

    public AuthUserAccessor(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /**
     * Resolves the logged-in {@link AppUser} from JWT (username principal). Empty for API-token auth.
     */
    public Optional<AppUser> currentAppUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof String username) || !StringUtils.hasText(username)) {
            return Optional.empty();
        }
        if (username.startsWith("api-token:")) {
            return Optional.empty();
        }
        return userRepo.findByUsername(username);
    }

    public AppUser requireAppUser() {
        return currentAppUser()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT login required"));
    }
}
