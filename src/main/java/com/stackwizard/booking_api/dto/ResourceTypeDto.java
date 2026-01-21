package com.stackwizard.booking_api.dto;

import lombok.Data;

@Data
public class ResourceTypeDto {
    private Long tenantId;
    private String code;
    private String name;
    private String defaultTimeModel;
}
