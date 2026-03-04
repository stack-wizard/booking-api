package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class ResourceMapPeriodsResponse {
    private Long tenantId;
    private Long locationId;
    private LocalDate fromDate;
    private LocalDate firstDate;
    private LocalDate lastDate;
    private List<ResourceMapPeriodDto> periods;
}
