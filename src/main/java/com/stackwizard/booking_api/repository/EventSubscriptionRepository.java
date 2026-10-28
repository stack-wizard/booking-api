package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.EventSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventSubscriptionRepository extends JpaRepository<EventSubscription, Long> {
    List<EventSubscription> findByTenantIdOrderByIdAsc(Long tenantId);

    List<EventSubscription> findByTenantIdAndActiveTrue(Long tenantId);

    Optional<EventSubscription> findByIdAndTenantId(Long id, Long tenantId);
}
