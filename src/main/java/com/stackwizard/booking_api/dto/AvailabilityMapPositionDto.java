package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AvailabilityMapPositionDto {
    private Long tenantId;
    private Long mapId;
    private String polygon;
    private String label;
}
