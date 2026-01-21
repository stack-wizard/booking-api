package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
@Builder
public class AvailabilityResourceDto {
    private Long tenantId;
    private Long id;
    private String code;
    private String name;
    private String typeCode;
    private Long locationId;
    private String status;
    private Boolean canBookAlone;
    private String colorHex;
    private Integer displayOrder;
    private LocalTime serviceWindowStart;
    private LocalTime serviceWindowEnd;
    private List<AvailabilitySlotDto> availableSlots;
    private List<AvailabilitySlotDto> gridSlots;
    private List<AvailabilityPriceDto> prices;
    private List<AvailabilityResourceRefDto> parents;
    private List<AvailabilityResourceRefDto> components;
    private AvailabilityMapPositionDto map;
}
