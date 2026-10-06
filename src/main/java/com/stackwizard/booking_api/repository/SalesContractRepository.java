package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.SalesContract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SalesContractRepository extends JpaRepository<SalesContract, Long> {
    Optional<SalesContract> findByIdAndTenantId(Long id, Long tenantId);

    List<SalesContract> findByTenantIdAndEventIdOrderByIdDesc(Long tenantId, Long eventId);

    Optional<SalesContract> findFirstByTenantIdAndEventIdAndStatusNot(Long tenantId, Long eventId, SalesContract.Status status);

    @Query("select count(c) from SalesContract c where c.tenantId = :tenantId and c.contractNumber like :prefix")
    long countByNumberPrefix(@Param("tenantId") Long tenantId, @Param("prefix") String prefix);
}
