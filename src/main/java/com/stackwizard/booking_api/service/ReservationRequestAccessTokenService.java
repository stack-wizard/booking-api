package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.ReservationRequestAccessToken;
import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import com.stackwizard.booking_api.repository.ReservationRequestAccessTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReservationRequestAccessTokenService {
    private static final String INTEGRATION_TYPE_BOOKING = "BOOKING";
    private static final String PROVIDER_EMAIL = "EMAIL";

    private final ReservationRequestAccessTokenRepository tokenRepository;
    private final TenantIntegrationConfigService tenantIntegrationConfigService;
    private final String publicBaseUrl;
    private final String publicAccessUrlTemplate;

    public ReservationRequestAccessTokenService(ReservationRequestAccessTokenRepository tokenRepository,
                                                TenantIntegrationConfigService tenantIntegrationConfigService,
                                                @Value("${booking.public-base-url:}") String publicBaseUrl,
                                                @Value("${booking.public-access-url-template:}") String publicAccessUrlTemplate) {
        this.tokenRepository = tokenRepository;
        this.tenantIntegrationConfigService = tenantIntegrationConfigService;
        this.publicBaseUrl = publicBaseUrl;
        this.publicAccessUrlTemplate = publicAccessUrlTemplate;
    }

    @Transactional
    public ReservationRequestAccessToken ensureActiveTokenForFinalizedRequest(ReservationRequest request, List<Reservation> reservations) {
        OffsetDateTime now = OffsetDateTime.now();
        List<ReservationRequestAccessToken> activeTokens =
                tokenRepository.findByReservationRequestIdAndRevokedAtIsNullOrderByCreatedAtDesc(request.getId());

        Optional<ReservationRequestAccessToken> reusable = activeTokens.stream()
                .filter(token -> token.getExpiresAt() != null && token.getExpiresAt().isAfter(now))
                .findFirst();
        if (reusable.isPresent()) {
            return reusable.get();
        }

        for (ReservationRequestAccessToken token : activeTokens) {
            token.setRevokedAt(now);
        }
        if (!activeTokens.isEmpty()) {
            tokenRepository.saveAll(activeTokens);
        }

        ReservationRequestAccessToken newToken = ReservationRequestAccessToken.builder()
                .tenantId(request.getTenantId())
                .reservationRequestId(request.getId())
                .token(UUID.randomUUID().toString())
                .expiresAt(calculatePublicAccessExpiry(reservations))
                .build();
        return tokenRepository.save(newToken);
    }

    @Transactional
    public ReservationRequestAccessToken requireValidToken(String tokenValue) {
        if (!StringUtils.hasText(tokenValue)) {
            throw new IllegalArgumentException("Token is required");
        }
        ReservationRequestAccessToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid public access token"));

        OffsetDateTime now = OffsetDateTime.now();
        if (token.getRevokedAt() != null) {
            throw new IllegalStateException("Public access token is revoked");
        }
        if (token.getExpiresAt() != null && !token.getExpiresAt().isAfter(now)) {
            token.setRevokedAt(now);
            tokenRepository.save(token);
            throw new IllegalStateException("Public access token is expired");
        }

        token.setLastUsedAt(now);
        return tokenRepository.save(token);
    }

    @Transactional(readOnly = true)
    public Optional<ReservationRequestAccessToken> findActiveByReservationRequestId(Long reservationRequestId) {
        OffsetDateTime now = OffsetDateTime.now();
        return tokenRepository.findByReservationRequestIdAndRevokedAtIsNullOrderByCreatedAtDesc(reservationRequestId)
                .stream()
                .filter(token -> token.getExpiresAt() != null && token.getExpiresAt().isAfter(now))
                .findFirst();
    }

    public String buildPublicAccessUrl(Long tenantId, String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String resolvedTemplate = resolvePublicAccessUrlTemplate(tenantId);
        if (StringUtils.hasText(resolvedTemplate) && resolvedTemplate.contains("{token}")) {
            return resolvedTemplate.replace("{token}", token.trim());
        }
        String path = "/api/public/reservation-requests/access/" + token;
        if (!StringUtils.hasText(publicBaseUrl)) {
            return path;
        }
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return base + path;
    }

    private String resolvePublicAccessUrlTemplate(Long tenantId) {
        if (tenantId != null) {
            Optional<TenantIntegrationConfig> config = tenantIntegrationConfigService
                    .findByTenantIdAndTypeAndProvider(tenantId, INTEGRATION_TYPE_BOOKING, PROVIDER_EMAIL);
            if (config.isPresent() && StringUtils.hasText(config.get().getPublicAccessUrlTemplate())) {
                return config.get().getPublicAccessUrlTemplate().trim();
            }
        }
        return publicAccessUrlTemplate;
    }

    private OffsetDateTime calculatePublicAccessExpiry(List<Reservation> reservations) {
        LocalDate maxCheckoutDate = reservations.stream()
                .map(Reservation::getEndsAt)
                .filter(v -> v != null)
                .map(LocalDateTime::toLocalDate)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.now(ZoneOffset.UTC));

        // Valid through checkout date + 1 full day; expires at start of next day (UTC).
        return maxCheckoutDate.plusDays(2).atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
