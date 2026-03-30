package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PublicCancellationPreviewDto {
    private boolean canCancel;
    private String status;
    private String settlementMode;
    private String currency;
    private BigDecimal cancelledAmount;
    private BigDecimal releasedAmount;
    private BigDecimal refundAmount;
    private BigDecimal penaltyAmount;
    private BigDecimal creditAmount;
    private LocalDateTime freeCancellationUntil;
    private String policyText;
    private String message;
}
