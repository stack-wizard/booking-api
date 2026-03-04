package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.ReservationRequestAccessToken;
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
    private final ReservationRequestAccessTokenRepository tokenRepository;
    private final String publicBaseUrl;

    public ReservationRequestAccessTokenService(ReservationRequestAccessTokenRepository tokenRepository,
                                                @Value("${booking.public-base-url:}") String publicBaseUrl) {
        this.tokenRepository = tokenRepository;
        this.publicBaseUrl = publicBaseUrl;
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

    public String buildPublicAccessUrl(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String path = "/api/public/reservation-requests/access/" + token;
        if (!StringUtils.hasText(publicBaseUrl)) {
            return path;
        }
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        return base + path;
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
