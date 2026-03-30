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
@Table(name = "cancellation_request")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "reservation_request_id", nullable = false)
    private Long reservationRequestId;

    @Column(nullable = false)
    private String status;

    @Column(name = "settlement_mode", nullable = false)
    private String settlementMode;

    @Column(nullable = false)
    private String currency;

    @Column(name = "source_invoice_id")
    private Long sourceInvoiceId;

    @Column(name = "storno_invoice_id")
    private Long stornoInvoiceId;

    @Column(name = "credit_note_invoice_id")
    private Long creditNoteInvoiceId;

    @Column(name = "penalty_invoice_id")
    private Long penaltyInvoiceId;

    @Column(name = "final_invoice_id")
    private Long finalInvoiceId;

    @Column(name = "source_payment_transaction_id")
    private Long sourcePaymentTransactionId;

    @Column(name = "refund_payment_transaction_id")
    private Long refundPaymentTransactionId;

    @Column(name = "cancelled_amount", nullable = false)
    private BigDecimal cancelledAmount;

    @Column(name = "released_amount", nullable = false)
    private BigDecimal releasedAmount;

    @Column(name = "refund_amount", nullable = false)
    private BigDecimal refundAmount;

    @Column(name = "penalty_amount", nullable = false)
    private BigDecimal penaltyAmount;

    @Column(name = "credit_amount", nullable = false)
    private BigDecimal creditAmount;

    @Column(name = "reservation_request_url")
    private String reservationRequestUrl;

    @Column
    private String note;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
