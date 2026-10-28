package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyQualificationStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyQualificationStepRepository extends JpaRepository<LoyaltyQualificationStep, Long> {
    List<LoyaltyQualificationStep> findByRuleSetIdOrderByStepOrderAsc(Long ruleSetId);

    void deleteByRuleSetId(Long ruleSetId);
}
