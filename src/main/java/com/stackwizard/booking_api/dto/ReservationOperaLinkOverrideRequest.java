package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class ReservationOperaLinkOverrideRequest {
    private String hotelCode;
    private Long operaReservationId;
}
