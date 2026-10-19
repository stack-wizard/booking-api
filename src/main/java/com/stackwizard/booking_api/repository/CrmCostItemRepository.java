package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmCostItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CrmCostItemRepository extends JpaRepository<CrmCostItem, Long> {
    List<CrmCostItem> findByTenantIdAndBookingIdOrderByIdAsc(Long tenantId, Long bookingId);

    List<CrmCostItem> findByTenantIdAndBookingIdIn(Long tenantId, Collection<Long> bookingIds);

    List<CrmCostItem> findByTenantIdAndOpportunityIdAndBookingIdIsNullOrderByIdAsc(Long tenantId, Long opportunityId);

    Optional<CrmCostItem> findByIdAndTenantId(Long id, Long tenantId);
}
