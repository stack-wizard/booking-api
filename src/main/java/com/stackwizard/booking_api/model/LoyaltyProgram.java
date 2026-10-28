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
@Table(name = "loyalty_program")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyProgram {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "accrual_unit", nullable = false)
    private AccrualUnit accrualUnit;

    @Column(name = "member_number_prefix")
    private String memberNumberPrefix;

    @Column(name = "member_number_next", nullable = false)
    private Long memberNumberNext;

    @Enumerated(EnumType.STRING)
    @Column(name = "anonymous_pricing", nullable = false)
    private AnonymousPricing anonymousPricing;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum AccrualUnit {
        NIGHT, POINTS
    }

    public enum AnonymousPricing {
        NONE, SHOW_ENTRY_TIER, APPLY_ENTRY_TIER_ON_JOIN
    }
}
