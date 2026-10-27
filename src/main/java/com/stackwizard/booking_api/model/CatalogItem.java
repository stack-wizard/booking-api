package com.stackwizard.booking_api.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** A resource, product or package published in a sales catalog with its own public text. */
@Entity
@Table(name = "catalog_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "catalog_id", nullable = false)
    private Long catalogId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_kind", nullable = false)
    private Kind itemKind;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "product_id")
    private Long productId;

    /** Display group inside the catalog (HALL, CATERING, AV, SPOT, PACKAGE, ...). Free text. */
    @JsonProperty("group")
    @Column(name = "item_group")
    private String itemGroup;

    @Column(name = "public_name")
    private String publicName;

    @Column(name = "public_description", columnDefinition = "text")
    private String publicDescription;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(nullable = false)
    private Boolean published;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "min_pax")
    private Integer minPax;

    @Column(name = "max_pax")
    private Integer maxPax;

    @Enumerated(EnumType.STRING)
    @Column
    private ProductPackageListing.Duration duration;

    @Column(name = "default_start_time")
    private LocalTime defaultStartTime;

    @Column(name = "setup_style")
    private String setupStyle;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public boolean validOn(LocalDate date) {
        return date == null || (validFrom == null || !date.isBefore(validFrom)) && (validTo == null || !date.isAfter(validTo));
    }

    public enum Kind {
        RESOURCE, PRODUCT, PACKAGE
    }
}
