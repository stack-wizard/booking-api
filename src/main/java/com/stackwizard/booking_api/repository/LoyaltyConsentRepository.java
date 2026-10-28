package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoyaltyConsentRepository extends JpaRepository<LoyaltyConsent, Long> {
    List<LoyaltyConsent> findByMemberIdOrderByChannelAsc(Long memberId);

    Optional<LoyaltyConsent> findByMemberIdAndChannel(Long memberId, LoyaltyConsent.Channel channel);
}
