package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PortalAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PortalAccessTokenRepository extends JpaRepository<PortalAccessToken, Long> {
    Optional<PortalAccessToken> findByToken(String token);

    List<PortalAccessToken> findByTenantIdAndEventIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long tenantId, Long eventId);
}
