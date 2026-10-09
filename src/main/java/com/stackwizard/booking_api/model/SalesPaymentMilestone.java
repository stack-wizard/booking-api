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
@Table(name = "sales_payment_milestone")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesPaymentMilestone {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Hotel the row is about; null = chain-wide. */
    @Column(name = "property_tenant_id")
    private Long propertyTenantId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    private String label;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    private BigDecimal percent;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Kind {
        DEPOSIT, INTERIM, FINAL
    }

    public enum Status {
        PLANNED, INVOICED, CANCELLED
    }
}
