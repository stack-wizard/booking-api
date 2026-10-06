package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmCostItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CrmCostItemRepository extends JpaRepository<CrmCostItem, Long> {
    List<CrmCostItem> findByTenantIdAndEventIdOrderByIdAsc(Long tenantId, Long eventId);

    List<CrmCostItem> findByTenantIdAndEventIdIn(Long tenantId, Collection<Long> eventIds);

    List<CrmCostItem> findByTenantIdAndOpportunityIdAndEventIdIsNullOrderByIdAsc(Long tenantId, Long opportunityId);

    Optional<CrmCostItem> findByIdAndTenantId(Long id, Long tenantId);
}
