package com.stackwizard.booking_api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** Named set of non-overlapping seasons of a chain ({@code propertyTenantId} null) or one hotel. */
@Entity
@Table(name = "season_calendar")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeasonCalendar {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_tenant_id")
    private Long propertyTenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
