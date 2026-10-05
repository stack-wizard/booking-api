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
@Table(name = "crm_stage_requirement")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmStageRequirement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "stage_id", nullable = false)
    private Long stageId;

    @Column(name = "field_path", nullable = false)
    private String fieldPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Requirement requirement;

    @Column(name = "value_spec")
    private String valueSpec;

    @Column(nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Requirement {
        REQUIRED, MIN_VALUE, MAX_VALUE
    }
}
