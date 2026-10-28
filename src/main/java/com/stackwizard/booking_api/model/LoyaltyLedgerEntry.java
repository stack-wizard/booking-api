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
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "loyalty_ledger")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyLedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric", nullable = false)
    private Metric metric;

    @Column(name = "qty", nullable = false)
    private Integer qty;

    @Column(name = "night_date")
    private LocalDate nightDate;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private Source source;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "property_tenant_id")
    private Long propertyTenantId;

    @Column(name = "rule_set_id")
    private Long ruleSetId;

    @Column(name = "reversal_of_id")
    private Long reversalOfId;

    @Column(name = "operator_user_id")
    private Long operatorUserId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "is_exception", nullable = false)
    private Boolean isException;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Metric {
        QUALIFYING_NIGHT, BONUS_NIGHT, POINTS
    }

    public enum Source {
        STAY, OPENING_BALANCE, WALLET, REFERRAL, ADJUSTMENT, CLAIM, REVERSAL
    }
}
