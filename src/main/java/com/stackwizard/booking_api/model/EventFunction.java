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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "event_function")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventFunction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "resource_id")
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "function_type", nullable = false)
    private FunctionType functionType;

    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "setup_style")
    private ResourceSetupCapacity.SetupStyle setupStyle;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(name = "occupancy_starts_at", nullable = false)
    private LocalDateTime occupancyStartsAt;

    @Column(name = "occupancy_ends_at", nullable = false)
    private LocalDateTime occupancyEndsAt;

    private Integer pax;

    @Column(name = "package_product_id")
    private Long packageProductId;

    private String notes;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum FunctionType {
        PLENARY, BREAKOUT, COFFEE_BREAK, LUNCH, DINNER, RECEPTION, EXHIBITION, OTHER
    }
}
