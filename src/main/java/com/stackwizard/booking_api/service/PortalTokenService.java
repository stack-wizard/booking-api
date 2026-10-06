package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.PortalAccessToken;
import com.stackwizard.booking_api.repository.PortalAccessTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Anonymous client links to an event's portal, modelled on reservation_request_access_token.
 * A link lives until 30 days after the event and is revoked on demand.
 */
@Service
public class PortalTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int DAYS_AFTER_EVENT = 30;

    private final PortalAccessTokenRepository tokenRepo;
    private final String portalBaseUrl;

    public PortalTokenService(PortalAccessTokenRepository tokenRepo,
                              @Value("${crm.portal.base-url:}") String portalBaseUrl) {
        this.tokenRepo = tokenRepo;
        this.portalBaseUrl = portalBaseUrl;
    }

    @Transactional
    public PortalAccessToken ensureToken(Event event, Long createdBy) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = expiryFor(event);
        List<PortalAccessToken> active = tokenRepo.findByTenantIdAndEventIdAndRevokedAtIsNullOrderByCreatedAtDesc(
                event.getTenantId(), event.getId());
        Optional<PortalAccessToken> reusable = active.stream().filter(t -> t.getExpiresAt().isAfter(now)).findFirst();
        if (reusable.isPresent()) {
            PortalAccessToken token = reusable.get();
            if (token.getExpiresAt().isBefore(expiresAt)) {
                token.setExpiresAt(expiresAt);
            }
            if (token.getContactId() == null && event.getPrimaryContactId() != null) {
                token.setContactId(event.getPrimaryContactId());
            }
            return tokenRepo.save(token);
        }
        return tokenRepo.save(PortalAccessToken.builder()
                .tenantId(event.getTenantId())
                .eventId(event.getId())
                .contactId(event.getPrimaryContactId())
                .token(newTokenValue())
                .expiresAt(expiresAt)
                .createdBy(createdBy)
                .build());
    }

    public Optional<PortalAccessToken> activeToken(Event event) {
        OffsetDateTime now = OffsetDateTime.now();
        return tokenRepo.findByTenantIdAndEventIdAndRevokedAtIsNullOrderByCreatedAtDesc(event.getTenantId(), event.getId())
                .stream().filter(t -> t.getExpiresAt().isAfter(now)).findFirst();
    }

    @Transactional
    public void revokeAll(Event event) {
        OffsetDateTime now = OffsetDateTime.now();
        List<PortalAccessToken> active = tokenRepo.findByTenantIdAndEventIdAndRevokedAtIsNullOrderByCreatedAtDesc(
                event.getTenantId(), event.getId());
        active.forEach(t -> t.setRevokedAt(now));
        tokenRepo.saveAll(active);
    }

    /** Valid, unrevoked and unexpired token; marks it as used. */
    @Transactional
    public PortalAccessToken requireValid(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Token is required");
        }
        PortalAccessToken token = tokenRepo.findByToken(value.trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid portal link"));
        OffsetDateTime now = OffsetDateTime.now();
        if (token.getRevokedAt() != null) {
            throw new IllegalStateException("Portal link was revoked");
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw new IllegalStateException("Portal link has expired");
        }
        token.setLastUsedAt(now);
        return tokenRepo.save(token);
    }

    public String portalUrl(PortalAccessToken token) {
        if (token == null) {
            return null;
        }
        if (!StringUtils.hasText(portalBaseUrl)) {
            return token.getToken();
        }
        String base = portalBaseUrl.endsWith("/") ? portalBaseUrl : portalBaseUrl + "/";
        return base + token.getToken();
    }

    static OffsetDateTime expiryFor(Event event) {
        OffsetDateTime afterEvent = event.getDateTo().plusDays(DAYS_AFTER_EVENT).atTime(LocalTime.MAX).atOffset(ZoneOffset.UTC);
        OffsetDateTime minimum = OffsetDateTime.now().plusDays(DAYS_AFTER_EVENT);
        return afterEvent.isAfter(minimum) ? afterEvent : minimum;
    }

    static String newTokenValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
