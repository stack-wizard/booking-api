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
@Table(name = "loyalty_tier_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyTierHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "from_tier_id")
    private Long fromTierId;

    @Column(name = "to_tier_id")
    private Long toTierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private Reason reason;

    @Column(name = "rule_set_id")
    private Long ruleSetId;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "operator_user_id")
    private Long operatorUserId;

    @Column(name = "comment")
    private String comment;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Reason {
        MIGRATION, UPGRADE, EXPIRY, ADJUSTMENT, ANNUAL_CYCLE, RECOMPUTE
    }
}
