package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<Resource, Long> {
    List<Resource> findByTenantId(Long tenantId);
    List<Resource> findByTenantIdAndLocationId(Long tenantId, Long locationId);

    @Query("select r from Resource r join fetch r.resourceType where r.id = :id")
    Optional<Resource> findByIdWithType(@Param("id") Long id);
}
