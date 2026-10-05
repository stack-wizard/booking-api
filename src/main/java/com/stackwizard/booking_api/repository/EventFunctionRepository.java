package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.EventFunction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventFunctionRepository extends JpaRepository<EventFunction, Long> {
    Optional<EventFunction> findByIdAndTenantId(Long id, Long tenantId);

    List<EventFunction> findByTenantIdAndEventIdOrderByStartsAtAscDisplayOrderAscIdAsc(Long tenantId, Long eventId);

    List<EventFunction> findByTenantIdAndIdIn(Long tenantId, Collection<Long> ids);

    boolean existsByTenantIdAndEventIdAndResourceIdIsNotNull(Long tenantId, Long eventId);

    List<EventFunction> findByTenantIdAndResourceIdIsNotNullAndOccupancyStartsAtLessThanAndOccupancyEndsAtGreaterThan(
            Long tenantId, LocalDateTime to, LocalDateTime from);
}
