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
@Table(name = "crm_stage_transition")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmStageTransition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "opportunity_id", nullable = false)
    private Long opportunityId;

    @Column(name = "from_stage_id")
    private Long fromStageId;

    @Column(name = "to_stage_id", nullable = false)
    private Long toStageId;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime changedAt;

    private String note;
}
