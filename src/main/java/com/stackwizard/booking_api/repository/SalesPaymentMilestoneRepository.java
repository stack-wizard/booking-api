package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.SalesPaymentMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SalesPaymentMilestoneRepository extends JpaRepository<SalesPaymentMilestone, Long> {
    List<SalesPaymentMilestone> findByTenantIdAndContractIdOrderByDisplayOrderAscIdAsc(Long tenantId, Long contractId);

    Optional<SalesPaymentMilestone> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select m from SalesPaymentMilestone m
            where m.status = com.stackwizard.booking_api.model.SalesPaymentMilestone.Status.PLANNED
              and m.dueDate <= :until
            """)
    List<SalesPaymentMilestone> findPlannedDueUntil(@Param("until") LocalDate until);
}
