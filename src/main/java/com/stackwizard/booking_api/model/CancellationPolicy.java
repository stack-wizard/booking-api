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

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "cancellation_policy")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationPolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private Boolean active;

    @Column(nullable = false)
    private Integer priority;

    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "cutoff_days_before_start", nullable = false)
    private Integer cutoffDaysBeforeStart;

    @Column(name = "before_cutoff_release_type", nullable = false)
    private String beforeCutoffReleaseType;

    @Column(name = "before_cutoff_release_value", nullable = false)
    private BigDecimal beforeCutoffReleaseValue;

    @Column(name = "before_cutoff_allow_cash_refund", nullable = false)
    private Boolean beforeCutoffAllowCashRefund;

    @Column(name = "before_cutoff_allow_customer_credit", nullable = false)
    private Boolean beforeCutoffAllowCustomerCredit;

    @Column(name = "before_cutoff_default_settlement_mode")
    private String beforeCutoffDefaultSettlementMode;

    @Column(name = "after_cutoff_release_type", nullable = false)
    private String afterCutoffReleaseType;

    @Column(name = "after_cutoff_release_value", nullable = false)
    private BigDecimal afterCutoffReleaseValue;

    @Column(name = "after_cutoff_allow_cash_refund", nullable = false)
    private Boolean afterCutoffAllowCashRefund;

    @Column(name = "after_cutoff_allow_customer_credit", nullable = false)
    private Boolean afterCutoffAllowCustomerCredit;

    @Column(name = "after_cutoff_default_settlement_mode")
    private String afterCutoffDefaultSettlementMode;

    @Column(name = "effective_from")
    private OffsetDateTime effectiveFrom;

    @Column(name = "effective_to")
    private OffsetDateTime effectiveTo;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
