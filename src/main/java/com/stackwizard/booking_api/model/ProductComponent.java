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
@Table(name = "product_component")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductComponent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "package_product_id", nullable = false)
    private Long packageProductId;

    @Column(name = "component_product_id", nullable = false)
    private Long componentProductId;

    @Column(nullable = false)
    private Integer qty;

    @Enumerated(EnumType.STRING)
    @Column(name = "qty_basis", nullable = false)
    private QtyBasis qtyBasis;

    @Column(nullable = false)
    private Boolean included;

    @Column(name = "share_percent")
    private BigDecimal sharePercent;

    @Column(name = "fixed_amount")
    private BigDecimal fixedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "function_type")
    private EventFunction.FunctionType functionType;

    @Column(name = "start_offset_minutes")
    private Integer startOffsetMinutes;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum QtyBasis {
        FIXED, PER_PAX
    }
}
