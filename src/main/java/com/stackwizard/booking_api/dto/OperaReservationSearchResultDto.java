package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OperaReservationSearchResultDto {
    private Long reservationId;
    private String hotelCode;
    private String hotelName;
    private String confirmationNumber;
    private String roomId;
    private String arrivalDate;
    private String departureDate;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String operaProfileId;
}
