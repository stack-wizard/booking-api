package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResourceTypeRepository extends JpaRepository<ResourceType, Long> {
    Optional<ResourceType> findByCodeIgnoreCase(String code);
}
