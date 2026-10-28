package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyMemberIdentifier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoyaltyMemberIdentifierRepository extends JpaRepository<LoyaltyMemberIdentifier, Long> {
    Optional<LoyaltyMemberIdentifier> findByTenantIdAndTypeAndValueNorm(Long tenantId, LoyaltyMemberIdentifier.Type type, String valueNorm);

    List<LoyaltyMemberIdentifier> findByMemberIdOrderByIdAsc(Long memberId);

    void deleteByMemberId(Long memberId);
}
