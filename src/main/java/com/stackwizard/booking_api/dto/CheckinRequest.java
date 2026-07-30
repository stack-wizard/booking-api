package com.stackwizard.booking_api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckinRequest {
    /**
     * When true, skip Opera create/check-in/deposit and final-invoice Opera posting.
     * Use for legacy stays already managed in Opera that only need local booking-api check-in.
     * When {@link #finalInvoiceOperaReservationId} is set, create/check-in are still skipped but
     * deposit payment and final-invoice charges post to that Opera reservation id.
     */
    private boolean skipOperaCheckIn;

    /**
     * Broken multi-day repair: skip Opera create/check-in on closed stays, then post deposit
     * payment and final-invoice charges to this open Opera reservation id.
     */
    private Long finalInvoiceOperaReservationId;

    /**
     * INHOUSE only: when true, post final invoice to {@code linkedOperaReservationId}.
     * Default false — lounge is usually included in the room rate and must not hit OHIP.
     * Ignored for EXTERNAL / WALKIN (those still auto-post unless {@link #skipOperaCheckIn}).
     */
    private boolean postToOpera;
}
