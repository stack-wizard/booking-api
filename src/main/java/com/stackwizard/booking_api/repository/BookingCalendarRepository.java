package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingCalendar;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingCalendarRepository extends JpaRepository<BookingCalendar, Long> {
    String PROPERTY_NODE_TYPE = "PROPERTY";

    Optional<BookingCalendar> findFirstByTenantIdAndLocationNodeId(Long tenantId, Long locationNodeId);
    Optional<BookingCalendar> findFirstByTenantIdAndLocationNode_NodeTypeOrderByIdAsc(Long tenantId, String nodeType);
    Optional<BookingCalendar> findFirstByTenantIdAndLocationNodeIsNull(Long tenantId);
    Optional<BookingCalendar> findFirstByTenantIdAndLocationNode_ParentIsNullOrderByIdAsc(Long tenantId);
    Optional<BookingCalendar> findFirstByTenantIdOrderByIdAsc(Long tenantId);

    default Optional<BookingCalendar> findFirstByTenantIdForMissingNode(Long tenantId) {
        return findFirstByTenantIdAndLocationNode_NodeTypeOrderByIdAsc(tenantId, PROPERTY_NODE_TYPE)
                .or(() -> findFirstByTenantIdAndLocationNodeIsNull(tenantId))
                .or(() -> findFirstByTenantIdAndLocationNode_ParentIsNullOrderByIdAsc(tenantId))
                .or(() -> findFirstByTenantIdOrderByIdAsc(tenantId));
    }
}
