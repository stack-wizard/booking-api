package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.FunctionSpace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FunctionSpaceRepository extends JpaRepository<FunctionSpace, Long> {
    List<FunctionSpace> findByTenantIdOrderByIdAsc(Long tenantId);

    Optional<FunctionSpace> findByIdAndTenantId(Long id, Long tenantId);

    Optional<FunctionSpace> findByTenantIdAndResourceId(Long tenantId, Long resourceId);

    List<FunctionSpace> findByTenantIdAndActiveTrueOrderByIdAsc(Long tenantId);
}
