package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CatalogItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CatalogItemRepository extends JpaRepository<CatalogItem, Long> {
    @Query("""
            select i from CatalogItem i
            where i.catalogId = :catalogId
            order by i.displayOrder, i.id
            """)
    List<CatalogItem> findForCatalog(@Param("catalogId") Long catalogId);

    Optional<CatalogItem> findByIdAndTenantId(Long id, Long tenantId);

    Optional<CatalogItem> findByCatalogIdAndProductId(Long catalogId, Long productId);

    Optional<CatalogItem> findByCatalogIdAndResourceId(Long catalogId, Long resourceId);

    List<CatalogItem> findByTenantIdAndProductIdOrderByCatalogIdAsc(Long tenantId, Long productId);
}
