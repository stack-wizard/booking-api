package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class OperaReservationAttachRequest {
    private String hotelCode;
    private Long reservationId;
    private String confirmationNumber;
    private String roomId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String operaProfileId;
}
