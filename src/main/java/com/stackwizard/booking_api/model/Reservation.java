package com.stackwizard.booking_api.model;

 
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;



@Entity
@Table(name = "reservation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "product_id")
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id")
    private ReservationRequest request;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false)
    private ReservationRequest.Type requestType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_resource_id", nullable = false)
    private Resource requestedResource;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Integer adults;

    @Column(nullable = false)
    private Integer children;

    @Column(nullable = false)
    private Integer infants;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "currency")
    private String currency;

    @Column(name = "qty")
    private Integer qty;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "gross_amount")
    private BigDecimal grossAmount;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;


    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
