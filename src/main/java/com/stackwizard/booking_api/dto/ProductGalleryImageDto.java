package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductGalleryImageDto {
    private Long id;
    private String imageUrl;
    private Boolean defaultImage;
    private Integer sortOrder;
}
