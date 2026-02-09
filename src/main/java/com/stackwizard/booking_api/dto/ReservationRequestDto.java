package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

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
    private List<ReservationSummaryDto> reservations;
}
