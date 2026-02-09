package com.stackwizard.booking_api.model;

 
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;



@Entity
@Table(name = "allocation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Allocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "product_id")
    private Long productId;

    @Column
    private String uom;

    @Column
    private Integer qty;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_resource_id", nullable = false)
    private Resource requestedResource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocated_resource_id", nullable = false)
    private Resource allocatedResource;

    @Column(name = "resource_kind", nullable = false)
    private String resourceKind;

    @Column(name = "composit_resource", nullable = false)
    private Boolean compositResource;

    @Column(name = "composit_resource_id")
    private Long compositResourceId;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(nullable = false)
    private String status;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "booked_range", columnDefinition = "tsrange", insertable = false, updatable = false)
    private String bookedRange;



    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
