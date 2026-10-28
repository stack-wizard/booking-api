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
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "loyalty_member")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Column(name = "member_number", nullable = false)
    private String memberNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "activated_at", nullable = false)
    private OffsetDateTime activatedAt;

    @Column(name = "wallet_added_at")
    private OffsetDateTime walletAddedAt;

    @Column(name = "wallet_exception_reason")
    private String walletExceptionReason;

    @Column(name = "assigned_tier_id")
    private Long assignedTierId;

    @Column(name = "computed_tier_id")
    private Long computedTierId;

    @Column(name = "tier_override", nullable = false)
    private Boolean tierOverride;

    @Column(name = "tier_valid_until")
    private LocalDate tierValidUntil;

    @Column(name = "tier_count_from")
    private LocalDate tierCountFrom;

    @Column(name = "last_stay_checkout")
    private LocalDate lastStayCheckout;

    @Column(name = "nights_total", nullable = false)
    private Integer nightsTotal;

    @Column(name = "nights_current_year", nullable = false)
    private Integer nightsCurrentYear;

    @Column(name = "nights_24m", nullable = false)
    private Integer nights24m;

    @Column(name = "counters_year")
    private Integer countersYear;

    @Column(name = "referrer_member_id")
    private Long referrerMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private Source source;

    @Column(name = "external_ref")
    private String externalRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", nullable = false, columnDefinition = "jsonb")
    private JsonNode attrs;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum Status {
        ACTIVE, INACTIVE, DEACTIVATED, ANONYMIZED
    }

    public enum Source {
        IMPORT, ADMIN, WEB, BOOKING
    }
}
