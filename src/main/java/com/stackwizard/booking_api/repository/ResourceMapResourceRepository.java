package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ResourceMapResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResourceMapResourceRepository extends JpaRepository<ResourceMapResource, Long> {
    List<ResourceMapResource> findByResourceMapIdIn(List<Long> mapIds);
    List<ResourceMapResource> findByResourceIdIn(List<Long> resourceIds);
}
