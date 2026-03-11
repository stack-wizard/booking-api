package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class ReservationRequestDto {
    private Long id;
    private Long tenantId;
    private String type;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String confirmationCode;
    private OffsetDateTime confirmedAt;
    private String publicAccessUrl;
    private OffsetDateTime publicAccessExpiresAt;
    private String qrPayload;
    private Integer extensionCount;
    private BigDecimal paymentTotalAmount;
    private BigDecimal paymentDueNowAmount;
    private BigDecimal paymentPaidAmount;
    private BigDecimal paymentRemainingAmount;
    private String paymentStatus;
    private LocalDateTime reservationStartsAt;
    private LocalDateTime reservationEndsAt;
    private List<ReservationSummaryDto> reservations;
}
