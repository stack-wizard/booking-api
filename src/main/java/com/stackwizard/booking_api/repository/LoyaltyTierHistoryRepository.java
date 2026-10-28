package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyTierHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyTierHistoryRepository extends JpaRepository<LoyaltyTierHistory, Long> {
    List<LoyaltyTierHistory> findByMemberIdOrderByIdDesc(Long memberId);
}
