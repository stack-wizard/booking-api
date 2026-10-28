package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyRuleSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoyaltyRuleSetRepository extends JpaRepository<LoyaltyRuleSet, Long> {
    List<LoyaltyRuleSet> findByProgramIdOrderByVersionDesc(Long programId);

    Optional<LoyaltyRuleSet> findByIdAndTenantId(Long id, Long tenantId);

    @Query("select coalesce(max(r.version), 0) from LoyaltyRuleSet r where r.programId = :programId")
    int maxVersion(Long programId);

    @Query("""
            select r from LoyaltyRuleSet r
            where r.programId = :programId and r.status = com.stackwizard.booking_api.model.LoyaltyRuleSet.Status.ACTIVE
              and r.effectiveFrom <= :date and (r.effectiveTo is null or r.effectiveTo > :date)
            """)
    Optional<LoyaltyRuleSet> findActiveOn(Long programId, LocalDate date);

    @Query("""
            select r from LoyaltyRuleSet r
            where r.programId = :programId and r.status = com.stackwizard.booking_api.model.LoyaltyRuleSet.Status.ACTIVE
            order by r.effectiveFrom desc
            """)
    List<LoyaltyRuleSet> findActive(Long programId);
}
