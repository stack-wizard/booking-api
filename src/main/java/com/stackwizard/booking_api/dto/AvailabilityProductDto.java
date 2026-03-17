package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AvailabilityProductDto {
    private Long tenantId;
    private Long productId;
    private String productName;
    private Integer displayOrder;
    private String description;
    private String defaultImageUrl;
    private List<ProductGalleryImageDto> galleryImages;
}
