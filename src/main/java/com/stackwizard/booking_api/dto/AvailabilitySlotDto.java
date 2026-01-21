package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;

@Data
@Builder
public class AvailabilitySlotDto {
    private LocalTime start;
    private LocalTime end;
    private String gridSlotStatus;
}
