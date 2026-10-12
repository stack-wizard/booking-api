package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "resource_setup_capacity")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceSetupCapacity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    /** Code from the chain's SETUP_STYLE code list ({@code crm_lookup}). */
    @Column(name = "setup_style", nullable = false)
    private String setupStyle;

    @Column(nullable = false)
    private Integer capacity;

    private String notes;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
