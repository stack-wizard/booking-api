package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.FunctionSpace;
import com.stackwizard.booking_api.model.FunctionSpaceSetup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FunctionSpaceSetupRepository extends JpaRepository<FunctionSpaceSetup, Long> {
    List<FunctionSpaceSetup> findByTenantIdAndFunctionSpaceIdOrderBySetupStyleAsc(Long tenantId, Long functionSpaceId);

    Optional<FunctionSpaceSetup> findByIdAndTenantId(Long id, Long tenantId);

    Optional<FunctionSpaceSetup> findByTenantIdAndFunctionSpaceIdAndSetupStyle(
            Long tenantId, Long functionSpaceId, FunctionSpace.SetupStyle setupStyle);

    List<FunctionSpaceSetup> findByTenantIdAndCapacityGreaterThanEqualOrderByCapacityAsc(Long tenantId, Integer minCapacity);
}
