package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PriceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PriceProfileRepository extends JpaRepository<PriceProfile, Long> {
    List<PriceProfile> findByTenantIdOrderByIdAsc(Long tenantId);

    Optional<PriceProfile> findByIdAndTenantId(Long id, Long tenantId);
}
