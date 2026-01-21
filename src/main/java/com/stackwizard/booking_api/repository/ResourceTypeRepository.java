package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceTypeRepository extends JpaRepository<ResourceType, Long> {
}
