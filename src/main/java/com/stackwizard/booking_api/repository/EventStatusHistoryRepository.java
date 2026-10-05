package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.EventStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventStatusHistoryRepository extends JpaRepository<EventStatusHistory, Long> {
    List<EventStatusHistory> findByTenantIdAndEventIdOrderByCreatedAtAscIdAsc(Long tenantId, Long eventId);
}
