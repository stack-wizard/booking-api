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
public class CheckinReadinessDto {
    /** True when POST check-in is expected to succeed (no blocking issues). */
    private boolean eligible;
    private List<String> issues;
    /**
     * True when Opera check-in is enabled for this tenant/request and can be skipped via
     * {@code CheckinRequest.skipOperaCheckIn} (legacy stays already in Opera).
     */
    private boolean operaCheckInSkippable;
    /** Reservation line ids due for check-in today (service date &lt;= today, still CONFIRMED). */
    private List<Long> dueTodayReservationIds;
    /** Non-cancelled lines already checked in. */
    private List<Long> alreadyCheckedInReservationIds;
    /** Future CONFIRMED lines skipped until their service date. */
    private List<Long> futureReservationIds;
}
