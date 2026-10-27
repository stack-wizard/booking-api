package com.stackwizard.booking_api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/** Opening hours of a season for one location (or all locations when {@code locationNodeId} is null). */
@Entity
@Table(name = "season_operating")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeasonOperating {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(name = "location_node_id")
    private Long locationNodeId;

    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;

    @Column(name = "close_time", nullable = false)
    private LocalTime closeTime;

    @Column(name = "grid_minutes", nullable = false)
    private Integer gridMinutes;

    @Column(name = "min_duration_minutes", nullable = false)
    private Integer minDurationMinutes;

    @Column(name = "max_duration_minutes", nullable = false)
    private Integer maxDurationMinutes;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
