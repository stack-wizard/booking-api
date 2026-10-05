package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.EventFunctionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventFunctionItemRepository extends JpaRepository<EventFunctionItem, Long> {
    Optional<EventFunctionItem> findByIdAndTenantId(Long id, Long tenantId);

    List<EventFunctionItem> findByTenantIdAndEventFunctionIdOrderByServeAtAscDisplayOrderAscIdAsc(Long tenantId, Long eventFunctionId);

    List<EventFunctionItem> findByTenantIdAndEventFunctionIdIn(Long tenantId, Collection<Long> eventFunctionIds);
}
