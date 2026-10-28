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
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "crm_account_production")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmAccountProduction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "property_tenant_id", nullable = false)
    private Long propertyTenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    @Column(name = "stays", nullable = false)
    private Integer stays;

    @Column(name = "nights", nullable = false)
    private Integer nights;

    @Column(name = "room_revenue", nullable = false)
    private BigDecimal roomRevenue;

    @Column(name = "fb_revenue", nullable = false)
    private BigDecimal fbRevenue;

    @Column(name = "other_revenue", nullable = false)
    private BigDecimal otherRevenue;

    @Column(name = "total_revenue", nullable = false)
    private BigDecimal totalRevenue;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Role {
        COMPANY, AGENT, GROUP
    }
}
