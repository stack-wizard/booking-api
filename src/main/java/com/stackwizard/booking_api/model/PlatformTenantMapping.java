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
import java.util.UUID;

@Entity
@Table(name = "platform_tenant_mapping")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformTenantMapping {
    public enum Kind { ORG, PROPERTY }

    public enum Status { SETUP, ACTIVE, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private Long tenantId;

    @Column(name = "platform_tenant_id", nullable = false, unique = true)
    private UUID platformTenantId;

    /** Local tenant_id of the parent ORG; null for ORG rows. */
    @Column(name = "parent_tenant_id")
    private Long parentTenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    @Builder.Default
    private Kind kind = Kind.ORG;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "name")
    private String name;

    @Column(name = "hotel_code")
    private String hotelCode;

    @Column(name = "timezone")
    private String timezone;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
