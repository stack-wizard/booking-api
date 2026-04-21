package com.stackwizard.booking_api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRequestAmendmentReplacementDto {
    @NotNull
    private Long cancelReservationId;
    @NotNull
    private Long newRequestedResourceId;

    /**
     * Optional calendar start date ({@code YYYY-MM-DD}). When set, the new reservation uses this date with the same
     * local time-of-day and duration as the line being replaced.
     */
    private String newStayStartDate;
}
