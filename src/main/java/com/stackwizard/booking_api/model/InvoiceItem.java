package com.stackwizard.booking_api.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "invoice_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonIgnore
    private Invoice invoice;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price_gross", nullable = false)
    private BigDecimal unitPriceGross;

    @Column(name = "discount_percent", nullable = false)
    private BigDecimal discountPercent;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "price_without_tax", nullable = false)
    private BigDecimal priceWithoutTax;

    @Column(name = "tax1_percent", nullable = false)
    private BigDecimal tax1Percent;

    @Column(name = "tax2_percent", nullable = false)
    private BigDecimal tax2Percent;

    @Column(name = "tax1_amount", nullable = false)
    private BigDecimal tax1Amount;

    @Column(name = "tax2_amount", nullable = false)
    private BigDecimal tax2Amount;

    @Column(name = "nett_price", nullable = false)
    private BigDecimal nettPrice;

    @Column(name = "gross_amount", nullable = false)
    private BigDecimal grossAmount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
