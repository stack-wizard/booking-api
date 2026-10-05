package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.EventOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventOrderRepository extends JpaRepository<EventOrder, Long> {
    Optional<EventOrder> findByIdAndTenantId(Long id, Long tenantId);

    List<EventOrder> findByTenantIdAndEventIdOrderByVersionDesc(Long tenantId, Long eventId);
}
