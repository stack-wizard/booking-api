package com.stackwizard.booking_api.model;

 
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;



@Entity
@Table(name = "resource")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Resource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_type_id", nullable = false)
    private ResourceType resourceType;

    @Column(nullable = false)
    private String kind; // POOL or EXACT

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_pool_id")
    private Resource parentPool;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private LocationNode location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(nullable = false)
    private String status;

    @Column(name = "color_hex")
    private String colorHex;

    @Column(name = "can_book_alone", nullable = false)
    private Boolean canBookAlone;

    @Column(name = "unit_count", nullable = false)
    private Integer unitCount;

    @Column(name = "pool_total_units")
    private Integer poolTotalUnits;

    @Column(name = "cap_adults", nullable = false)
    private Integer capAdults;

    @Column(name = "cap_children", nullable = false)
    private Integer capChildren;

    @Column(name = "cap_infants", nullable = false)
    private Integer capInfants;

    @Column(name = "cap_total", nullable = false)
    private Integer capTotal;



    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
