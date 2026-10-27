package com.stackwizard.booking_api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Seasons a catalog item is limited to; no rows = every season. */
@Entity
@Table(name = "catalog_item_season")
@IdClass(CatalogItemSeason.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogItemSeason {
    @Id
    @Column(name = "catalog_item_id", nullable = false)
    private Long catalogItemId;

    @Id
    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long catalogItemId;
        private Long seasonId;
    }
}
