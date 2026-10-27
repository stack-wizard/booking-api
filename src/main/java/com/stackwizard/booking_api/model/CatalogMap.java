package com.stackwizard.booking_api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Resource map used by a catalog, optionally only in one season (null = by the map's own validity). */
@Entity
@Table(name = "catalog_map")
@IdClass(CatalogMap.Key.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogMap {
    @Id
    @Column(name = "catalog_id", nullable = false)
    private Long catalogId;

    @Id
    @Column(name = "resource_map_id", nullable = false)
    private Long resourceMapId;

    @Column(name = "season_id")
    private Long seasonId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long catalogId;
        private Long resourceMapId;
    }
}
