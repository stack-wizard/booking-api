package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "function_space_setup")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FunctionSpaceSetup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "function_space_id", nullable = false)
    private Long functionSpaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "setup_style", nullable = false)
    private FunctionSpace.SetupStyle setupStyle;

    @Column(nullable = false)
    private Integer capacity;

    private String notes;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
