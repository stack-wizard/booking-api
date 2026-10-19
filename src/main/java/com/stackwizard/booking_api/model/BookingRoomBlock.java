package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "booking_room_block")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingRoomBlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "property_tenant_id")
    private Long propertyTenantId;

    @Column(name = "room_resource_id")
    private Long roomResourceId;

    @Column(name = "room_type_label")
    private String roomTypeLabel;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    /** Departure day; the last night is the day before. */
    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Builder.Default
    @Column(name = "pax_per_room", nullable = false)
    private Integer paxPerRoom = 2;

    @Column(name = "rate_price_profile_id")
    private Long ratePriceProfileId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
