package com.stackwizard.booking_api.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class BookingRequest {
    private Long tenantId;
    private Long productId;
    private Long resourceId;
    private String uom;
    private Integer qty;
    private String currency;
    private LocalDate serviceDate;
    private LocalTime startTime;
    private LocalDate endDate;
    private LocalTime endTime;
    private Integer adults;
    private Integer children;
    private Integer infants;
    private String customerName;
}
