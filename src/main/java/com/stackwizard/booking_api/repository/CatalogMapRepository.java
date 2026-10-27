package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CatalogMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CatalogMapRepository extends JpaRepository<CatalogMap, CatalogMap.Key> {
    List<CatalogMap> findByCatalogIdOrderByResourceMapIdAsc(Long catalogId);

    @Modifying
    @Query("delete from CatalogMap m where m.catalogId = :catalogId")
    void deleteByCatalogId(@Param("catalogId") Long catalogId);
}
