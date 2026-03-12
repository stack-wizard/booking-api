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

import java.time.OffsetDateTime;

@Entity
@Table(name = "opera_fiscal_payment_mapping")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperaFiscalPaymentMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "payment_type", nullable = false)
    private String paymentType;

    @Column(name = "trx_code", nullable = false)
    private String trxCode;

    @Column(name = "trx_type", nullable = false)
    private String trxType;

    @Column(name = "trx_code_type", nullable = false)
    private String trxCodeType;

    @Column(name = "trx_group")
    private String trxGroup;

    @Column(name = "trx_sub_group")
    private String trxSubGroup;

    @Column
    private String description;

    @Column(name = "bucket_code")
    private String bucketCode;

    @Column(name = "bucket_type")
    private String bucketType;

    @Column(name = "bucket_value")
    private String bucketValue;

    @Column(name = "bucket_description")
    private String bucketDescription;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
