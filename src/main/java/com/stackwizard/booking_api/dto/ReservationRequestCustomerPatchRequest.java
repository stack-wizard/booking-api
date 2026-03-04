package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class ReservationRequestCustomerPatchRequest {
    private String customerName;
    private String customerEmail;
    private String customerPhone;
}
