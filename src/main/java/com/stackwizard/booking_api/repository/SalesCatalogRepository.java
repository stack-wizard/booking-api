package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.SalesCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalesCatalogRepository extends JpaRepository<SalesCatalog, Long> {
    List<SalesCatalog> findByTenantIdOrderByCodeAscIdAsc(Long tenantId);

    List<SalesCatalog> findByTenantIdAndCodeIgnoreCase(Long tenantId, String code);

    Optional<SalesCatalog> findByIdAndTenantId(Long id, Long tenantId);

    List<SalesCatalog> findBySeasonCalendarId(Long seasonCalendarId);
}
