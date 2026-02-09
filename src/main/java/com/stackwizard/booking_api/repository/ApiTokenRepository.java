package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {
    Optional<ApiToken> findByTokenHashAndActiveTrue(String tokenHash);
}
