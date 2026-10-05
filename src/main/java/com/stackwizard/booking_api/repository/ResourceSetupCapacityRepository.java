package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ResourceSetupCapacity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ResourceSetupCapacityRepository extends JpaRepository<ResourceSetupCapacity, Long> {
    List<ResourceSetupCapacity> findByTenantIdAndResourceIdOrderBySetupStyleAsc(Long tenantId, Long resourceId);

    List<ResourceSetupCapacity> findByTenantIdAndResourceIdIn(Long tenantId, Collection<Long> resourceIds);

    List<ResourceSetupCapacity> findByTenantIdOrderByResourceIdAscSetupStyleAsc(Long tenantId);

    Optional<ResourceSetupCapacity> findByTenantIdAndResourceIdAndSetupStyle(Long tenantId,
                                                                            Long resourceId,
                                                                            ResourceSetupCapacity.SetupStyle setupStyle);

    @Query("""
            select c from ResourceSetupCapacity c
            where c.tenantId = :tenantId
              and c.capacity >= :minPax
              and (:setupStyle is null or c.setupStyle = :setupStyle)
            order by c.capacity asc, c.resourceId asc
            """)
    List<ResourceSetupCapacity> findCandidates(@Param("tenantId") Long tenantId,
                                               @Param("minPax") Integer minPax,
                                               @Param("setupStyle") ResourceSetupCapacity.SetupStyle setupStyle);

    @Modifying
    @Query("delete from ResourceSetupCapacity c where c.tenantId = :tenantId and c.resourceId = :resourceId")
    void deleteByTenantIdAndResourceId(@Param("tenantId") Long tenantId, @Param("resourceId") Long resourceId);
}
