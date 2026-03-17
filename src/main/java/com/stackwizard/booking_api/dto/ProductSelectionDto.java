package com.stackwizard.booking_api.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class ProductSelectionDto {
    private Long productId;
    private String productName;
    private Integer displayOrder;
    private String description;
    private String defaultImageUrl;
    private List<ProductGalleryImageDto> galleryImages;
    private BigDecimal tax1Percent;
    private BigDecimal tax2Percent;
    private BigDecimal unitPriceGross;
    private String currency;
    private String uom;
    private LocalDate priceDate;
}
