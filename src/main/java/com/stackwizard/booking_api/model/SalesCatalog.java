package com.stackwizard.booking_api.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** What is sold together on one web surface (BEACH, MICE, ...). The code is fixed after creation (CMS uses it). */
@Entity
@Table(name = "sales_catalog")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesCatalog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_tenant_id")
    private Long propertyTenantId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_mode", nullable = false)
    private SaleMode saleMode;

    @Column(name = "season_calendar_id")
    private Long seasonCalendarId;

    /** Channel used to pick prices (web = EXTERNAL). */
    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false)
    private ReservationRequest.Type requestType;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum SaleMode {
        INSTANT, INQUIRY
    }
}
