package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ResourceMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ResourceMapRepository extends JpaRepository<ResourceMap, Long> {
    List<ResourceMap> findByTenantId(Long tenantId);

    @Query(value = """
            select case when count(*) > 0 then true else false end
            from resource_map rm
            where rm.tenant_id = :tenantId
              and rm.parent_map_id is null
              and (:excludeId is null or rm.id <> :excludeId)
              and (
                   (rm.location_node_id is null and :locationNodeId is null)
                   or rm.location_node_id = :locationNodeId
              )
              and coalesce(rm.valid_to, 'infinity'::date) >= coalesce(cast(:validFrom as date), '-infinity'::date)
              and coalesce(cast(:validTo as date), 'infinity'::date) >= coalesce(rm.valid_from, '-infinity'::date)
            """, nativeQuery = true)
    boolean existsOverlappingRootMap(@Param("tenantId") Long tenantId,
                                     @Param("locationNodeId") Long locationNodeId,
                                     @Param("validFrom") String validFrom,
                                     @Param("validTo") String validTo,
                                     @Param("excludeId") Long excludeId);
}
