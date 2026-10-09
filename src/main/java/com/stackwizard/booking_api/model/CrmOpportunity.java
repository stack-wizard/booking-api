package com.stackwizard.booking_api.model;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "crm_opportunity")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmOpportunity extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Hotel the row is about; null = chain-wide. */
    @Column(name = "property_tenant_id")
    private Long propertyTenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "primary_contact_id")
    private Long primaryContactId;

    @Column(name = "pipeline_id", nullable = false)
    private Long pipelineId;

    @Column(name = "stage_id", nullable = false)
    private Long stageId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "agency_account_id")
    private Long agencyAccountId;

    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "expected_close_date")
    private LocalDate expectedCloseDate;

    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "outcome_reason_id")
    private Long outcomeReasonId;

    @Column(name = "outcome_note")
    private String outcomeNote;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb", nullable = false)
    private JsonNode attrs;

    public enum Status {
        OPEN, WON, LOST, TURNED_DOWN, CANCELLED
    }
}
