package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Resource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResourceRepository extends JpaRepository<Resource, Long> {
    List<Resource> findByTenantId(Long tenantId);
    List<Resource> findByTenantIdAndLocationId(Long tenantId, Long locationId);
}
