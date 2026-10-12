package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmLookup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmLookupRepository extends JpaRepository<CrmLookup, Long> {
    List<CrmLookup> findByTenantIdAndKindOrderByDisplayOrderAscIdAsc(Long tenantId, CrmLookup.Kind kind);

    Optional<CrmLookup> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByTenantIdAndKind(Long tenantId, CrmLookup.Kind kind);
}
