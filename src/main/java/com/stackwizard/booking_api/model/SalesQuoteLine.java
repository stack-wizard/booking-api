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
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "sales_quote_line")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesQuoteLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "quote_id", nullable = false)
    private Long quoteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_group", nullable = false)
    private Group lineGroup;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "event_function_id")
    private Long eventFunctionId;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "service_date")
    private LocalDate serviceDate;

    private String uom;

    @Column(nullable = false)
    private Integer qty;

    @Column(name = "base_rate", nullable = false)
    private BigDecimal baseRate;

    @Column(name = "offered_rate", nullable = false)
    private BigDecimal offeredRate;

    @Column(name = "tax1_percent", nullable = false)
    private BigDecimal tax1Percent;

    @Column(name = "tax2_percent", nullable = false)
    private BigDecimal tax2Percent;

    @Column(name = "amount_base", nullable = false)
    private BigDecimal amountBase;

    @Column(name = "amount_offered", nullable = false)
    private BigDecimal amountOffered;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Group {
        MEETING, F_AND_B, AV, EXTRAS, DISCOUNT
    }
}
