package com.stackwizard.booking_api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.repository.AppUserRepository;

import java.util.Optional;
import java.util.UUID;

@Component
public class AuthUserAccessor {

    private final AppUserRepository userRepo;

    public AuthUserAccessor(AppUserRepository userRepo) {
        this.userRepo = userRepo;
    }

    /**
     * Resolves the logged-in {@link AppUser} from Platform JWT ({@code sub} = platform_user_id).
     * Empty for m2m / anonymous.
     */
    public Optional<AppUser> currentAppUser() {
        AppUser fromRequest = requestAttributeUser();
        if (fromRequest != null) {
            return Optional.of(fromRequest);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            return Optional.empty();
        }
        String tokenUse = jwt.getClaimAsString("token_use");
        if (tokenUse != null && "m2m".equalsIgnoreCase(tokenUse.trim())) {
            return Optional.empty();
        }
        try {
            UUID platformUserId = UUID.fromString(jwt.getSubject());
            return userRepo.findByPlatformUserId(platformUserId);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }
    }

    public AppUser requireAppUser() {
        return currentAppUser()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User JWT login required"));
    }

    private static AppUser requestAttributeUser() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        Object value = request.getAttribute(PlatformAuthFilter.ATTR_APP_USER);
        return value instanceof AppUser appUser ? appUser : null;
    }
}
