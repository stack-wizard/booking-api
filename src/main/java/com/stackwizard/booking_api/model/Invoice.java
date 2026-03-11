package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "invoice")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "invoice_type", nullable = false)
    private String invoiceType;

    @Column(name = "invoice_number", nullable = false)
    private String invoiceNumber;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(nullable = false)
    private String status;

    @Column(name = "payment_status", nullable = false)
    private String paymentStatus;

    @Column(name = "fiscalization_status", nullable = false)
    private String fiscalizationStatus;

    @Column(name = "reference_table", nullable = false)
    private String referenceTable;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(name = "reservation_request_id")
    private Long reservationRequestId;

    @Column(name = "storno_id")
    private Long stornoId;

    @Column(nullable = false)
    private String currency;

    @Column(name = "subtotal_net", nullable = false)
    private BigDecimal subtotalNet;

    @Column(name = "discount_total", nullable = false)
    private BigDecimal discountTotal;

    @Column(name = "tax1_total", nullable = false)
    private BigDecimal tax1Total;

    @Column(name = "tax2_total", nullable = false)
    private BigDecimal tax2Total;

    @Column(name = "total_gross", nullable = false)
    private BigDecimal totalGross;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
