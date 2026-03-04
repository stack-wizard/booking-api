package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class ResourceMapPeriodDto {
    private Long mapId;
    private Long locationNodeId;
    private String mapName;
    private LocalDate validFrom;
    private LocalDate validTo;
}
