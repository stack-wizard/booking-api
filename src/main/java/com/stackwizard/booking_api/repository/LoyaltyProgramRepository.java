package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyProgram;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LoyaltyProgramRepository extends JpaRepository<LoyaltyProgram, Long> {
    List<LoyaltyProgram> findByTenantIdOrderByIdAsc(Long tenantId);

    Optional<LoyaltyProgram> findByIdAndTenantId(Long id, Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LoyaltyProgram p where p.id = :id")
    Optional<LoyaltyProgram> lockById(Long id);
}
