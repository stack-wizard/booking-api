package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class AvailabilityResponse {
    private Long tenantId;
    private LocalDate date;
    private Integer gridMinutes;
    private List<AvailabilityMapDto> maps;
    private List<AvailabilityResourceDto> resources;
}
