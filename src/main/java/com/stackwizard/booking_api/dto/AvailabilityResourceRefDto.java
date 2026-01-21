package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AvailabilityResourceRefDto {
    private Long tenantId;
    private Long id;
    private String code;
    private String name;
}
