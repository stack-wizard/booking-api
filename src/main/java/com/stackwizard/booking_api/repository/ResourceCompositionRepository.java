package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ResourceComposition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceCompositionRepository extends JpaRepository<ResourceComposition, Long> {
    java.util.List<ResourceComposition> findByParentResourceId(Long parentResourceId);
    java.util.List<ResourceComposition> findByParentResourceIdIn(java.util.List<Long> parentResourceIds);
    java.util.List<ResourceComposition> findByMemberResourceIdIn(java.util.List<Long> memberResourceIds);
}
