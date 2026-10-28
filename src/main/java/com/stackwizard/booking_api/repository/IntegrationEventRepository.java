package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.IntegrationEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface IntegrationEventRepository extends JpaRepository<IntegrationEvent, Long> {
    Optional<IntegrationEvent> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select e from IntegrationEvent e
            where e.tenantId = :tenantId and (:type is null or e.type = :type)
            order by e.id desc
            """)
    Page<IntegrationEvent> search(Long tenantId, String type, Pageable pageable);
}
