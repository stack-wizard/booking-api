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

@Entity
@Table(name = "invoice_sequence")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceSequence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "invoice_type", nullable = false)
    private String invoiceType;

    @Column(name = "invoice_year", nullable = false)
    private Integer invoiceYear;

    @Column(name = "last_number", nullable = false)
    private Integer lastNumber;
}
