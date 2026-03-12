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
import java.time.OffsetDateTime;

@Entity
@Table(name = "opera_fiscal_tax_mapping")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperaFiscalTaxMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "tax_percent", nullable = false)
    private BigDecimal taxPercent;

    @Column(name = "generate_trx_code", nullable = false)
    private String generateTrxCode;

    @Column(name = "tax_name")
    private String taxName;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
