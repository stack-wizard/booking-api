package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByTenantId(Long tenantId);

    List<Product> findByTenantIdOrderByDisplayOrderAscNameAscIdAsc(Long tenantId);

    Optional<Product> findByIdAndTenantId(Long id, Long tenantId);

    Page<Product> findByTenantIdOrderByDisplayOrderAscNameAscIdAsc(Long tenantId, Pageable pageable);

    Page<Product> findByTenantIdAndNameContainingIgnoreCaseOrderByDisplayOrderAscNameAscIdAsc(Long tenantId, String name, Pageable pageable);

    Optional<Product> findFirstByTenantIdAndProductTypeIgnoreCaseOrderByDisplayOrderAscIdAsc(Long tenantId, String productType);

    /**
     * Products of the chain that are offered in the hotel: ALL unless the hotel switched it off, SELECTED only
     * when the hotel switched it on.
     */
    @Query("""
            select p from Product p
            where p.tenantId = :orgTenantId
              and ((p.propertyVisibility = com.stackwizard.booking_api.model.Product.PropertyVisibility.ALL
                    and not exists (select 1 from ProductProperty pp
                                    where pp.productId = p.id and pp.tenantId = :propertyTenantId and pp.visible = false))
                or (p.propertyVisibility = com.stackwizard.booking_api.model.Product.PropertyVisibility.SELECTED
                    and exists (select 1 from ProductProperty pp
                                where pp.productId = p.id and pp.tenantId = :propertyTenantId and pp.visible = true)))
            order by p.displayOrder, p.name, p.id
            """)
    List<Product> findVisibleForProperty(@Param("orgTenantId") Long orgTenantId,
                                         @Param("propertyTenantId") Long propertyTenantId);
}
