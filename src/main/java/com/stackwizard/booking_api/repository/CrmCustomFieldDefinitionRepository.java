package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmCustomFieldDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmCustomFieldDefinitionRepository extends JpaRepository<CrmCustomFieldDefinition, Long> {
    List<CrmCustomFieldDefinition> findByTenantIdOrderByDisplayOrderAsc(Long tenantId);
    Optional<CrmCustomFieldDefinition> findByIdAndTenantId(Long id, Long tenantId);
}
