package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckinResultDto {
    private Long finalInvoiceId;
    /** Resulting reservation request status after this check-in. */
    private String requestStatus;
    private int checkedInReservationCount;
    private int remainingConfirmedReservationCount;
    private List<Long> checkedInReservationIds;
    private List<Long> remainingConfirmedReservationIds;
}
