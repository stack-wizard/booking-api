package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CatalogItemSeason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CatalogItemSeasonRepository extends JpaRepository<CatalogItemSeason, CatalogItemSeason.Key> {
    List<CatalogItemSeason> findByCatalogItemIdIn(Collection<Long> catalogItemIds);

    List<CatalogItemSeason> findByCatalogItemId(Long catalogItemId);

    @Modifying
    @Query("delete from CatalogItemSeason s where s.catalogItemId = :itemId")
    void deleteByCatalogItemId(@Param("itemId") Long itemId);
}
