package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmReassignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrmReassignmentRepository extends JpaRepository<CrmReassignment, Long> {
    List<CrmReassignment> findTop50ByTenantIdOrderByCreatedAtDesc(Long tenantId);
}
