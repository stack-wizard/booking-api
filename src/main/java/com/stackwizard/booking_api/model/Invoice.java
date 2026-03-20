package com.stackwizard.booking_api.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false)
    private InvoiceType invoiceType;

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

    @Column(name = "issued_by_user_id")
    private Long issuedByUserId;

    @Column(name = "issued_at")
    private OffsetDateTime issuedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "issued_by_mode", nullable = false)
    private IssuedByMode issuedByMode;

    @Column(name = "business_premise_id")
    private Long businessPremiseId;

    @Column(name = "cash_register_id")
    private Long cashRegisterId;

    @Column(name = "business_premise_code_snapshot")
    private String businessPremiseCodeSnapshot;

    @Column(name = "cash_register_code_snapshot")
    private String cashRegisterCodeSnapshot;

    @Column(name = "fiscalized_at")
    private OffsetDateTime fiscalizedAt;

    @Column(name = "fiscal_folio_no")
    private String fiscalFolioNo;

    @Column(name = "fiscal_document_no_1")
    private String fiscalDocumentNo1;

    @Column(name = "fiscal_document_no_2")
    private String fiscalDocumentNo2;

    @Column(name = "fiscal_special_id")
    private String fiscalSpecialId;

    @Column(name = "fiscal_qr_url")
    private String fiscalQrUrl;

    @Column(name = "fiscal_error_message")
    private String fiscalErrorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fiscal_last_request_payload", columnDefinition = "jsonb")
    private JsonNode fiscalLastRequestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fiscal_last_response_payload", columnDefinition = "jsonb")
    private JsonNode fiscalLastResponsePayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    @Column(name = "payment_status", nullable = false)
    private String paymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "fiscalization_status", nullable = false)
    private InvoiceFiscalizationStatus fiscalizationStatus;

    @Column(name = "reference_table")
    private String referenceTable;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reservation_request_id")
    private Long reservationRequestId;

    @Column(name = "storno_id")
    private Long stornoId;

    @Column(name = "opera_reservation_id")
    private Long operaReservationId;

    @Column(name = "opera_hotel_code")
    private String operaHotelCode;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "opera_posting_status", nullable = false)
    private OperaPostingStatus operaPostingStatus = OperaPostingStatus.NOT_POSTED;

    @Column(name = "opera_posted_at")
    private OffsetDateTime operaPostedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "opera_last_request_payload", columnDefinition = "jsonb")
    private JsonNode operaLastRequestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "opera_last_response_payload", columnDefinition = "jsonb")
    private JsonNode operaLastResponsePayload;

    @Column(name = "opera_error_message")
    private String operaErrorMessage;

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

    public OperaPostingTarget resolveOperaPostingTarget() {
        return invoiceType != null
                ? invoiceType.defaultOperaPostingTarget()
                : OperaPostingTarget.POSTING_MASTER;
    }
}
