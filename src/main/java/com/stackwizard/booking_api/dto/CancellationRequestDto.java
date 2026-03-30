package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
public class CancellationRequestDto {
    private Long id;
    private Long tenantId;
    private Long reservationRequestId;
    private String reservationRequestUrl;
    private String status;
    private String settlementMode;
    private String currency;
    private BigDecimal cancelledAmount;
    private BigDecimal releasedAmount;
    private BigDecimal refundAmount;
    private BigDecimal penaltyAmount;
    private BigDecimal creditAmount;
    private Long sourceInvoiceId;
    private Long stornoInvoiceId;
    private Long creditNoteInvoiceId;
    private Long penaltyInvoiceId;
    private Long finalInvoiceId;
    private Long sourcePaymentTransactionId;
    private Long refundPaymentTransactionId;
    private String note;
    private String failureReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
}
