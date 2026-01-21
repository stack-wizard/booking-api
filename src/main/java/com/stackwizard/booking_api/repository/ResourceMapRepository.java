package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ResourceMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResourceMapRepository extends JpaRepository<ResourceMap, Long> {
    List<ResourceMap> findByTenantId(Long tenantId);
}
