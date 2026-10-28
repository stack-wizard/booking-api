package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyTierRepository extends JpaRepository<LoyaltyTier, Long> {
    List<LoyaltyTier> findByRuleSetIdOrderByRankAsc(Long ruleSetId);

    void deleteByRuleSetId(Long ruleSetId);
}
