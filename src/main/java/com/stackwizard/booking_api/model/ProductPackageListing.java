package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "product_package_listing")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductPackageListing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "public_name")
    private String publicName;

    @Column(name = "public_description", columnDefinition = "text")
    private String publicDescription;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "min_pax")
    private Integer minPax;

    @Column(name = "max_pax")
    private Integer maxPax;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Duration duration;

    @Column(name = "default_start_time", nullable = false)
    private LocalTime defaultStartTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "setup_style")
    private ResourceSetupCapacity.SetupStyle setupStyle;

    @Column(nullable = false)
    private Boolean published;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum Duration {
        FULL_DAY, HALF_DAY, CUSTOM
    }
}
