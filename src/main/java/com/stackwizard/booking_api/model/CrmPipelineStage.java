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
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "crm_pipeline_stage")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmPipelineStage extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "pipeline_id", nullable = false)
    private Long pipelineId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(nullable = false)
    private BigDecimal probability;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage_kind", nullable = false)
    private StageKind stageKind;

    /** A deal in this open stage may move to an earlier open stage. */
    @Builder.Default
    @Column(name = "allow_back", nullable = false)
    private Boolean allowBack = true;

    /** A deal in this open stage may jump past the next open stage. */
    @Builder.Default
    @Column(name = "allow_skip", nullable = false)
    private Boolean allowSkip = true;

    /** Entering this stage locks the intake fields of the deal. */
    @Builder.Default
    @Column(name = "locks_intake", nullable = false)
    private Boolean locksIntake = false;

    /** Entering this stage moves the open bookings of the deal to this booking status ({@code booking_status.code}). */
    @Column(name = "booking_status_code")
    private String bookingStatusCode;

    public enum StageKind {
        OPEN, WON, LOST
    }
}
