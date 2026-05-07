package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Data
@Builder
public class ReservationSummaryDto {
    /** Same as {@link #id}; explicit name for admin/API clients. */
    private Long reservationId;
    private Long id;
    private Long tenantId;
    private Long productId;
    private String productName;
    private Long requestId;
    private String requestType;
    private Long requestedResourceId;
    private String requestedResourceCode;
    private String requestedResourceName;
    /** OHIP/Opera room id on the requested resource (for PMS check-in). */
    private String operaRoomId;
    /** OHIP reservation id after create reservation. */
    private Long operaReservationId;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private String status;
    private OffsetDateTime expiresAt;
    private Integer adults;
    private Integer children;
    private Integer infants;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String currency;
    private Integer qty;
    private BigDecimal unitPrice;
    private BigDecimal grossAmount;
    private String cancellationPolicyText;
}
