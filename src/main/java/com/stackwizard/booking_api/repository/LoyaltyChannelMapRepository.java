package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyChannelMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoyaltyChannelMapRepository extends JpaRepository<LoyaltyChannelMap, Long> {
    List<LoyaltyChannelMap> findByProgramIdOrderByIdAsc(Long programId);

    void deleteByProgramId(Long programId);
}
