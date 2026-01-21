package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AvailabilityMapDto {
    private Long tenantId;
    private Long mapId;
    private Long locationNodeId;
    private String name;
    private String imageUrl;
    private String svgOverlay;
}
