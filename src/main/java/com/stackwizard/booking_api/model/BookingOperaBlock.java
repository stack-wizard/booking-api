package com.stackwizard.booking_api.model;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "booking_opera_block")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingOperaBlock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "opera_hotel_id", nullable = false)
    private Long operaHotelId;

    @Column(name = "property_tenant_id", nullable = false)
    private Long propertyTenantId;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "block_id")
    private String blockId;

    @Column(name = "block_code")
    private String blockCode;

    @Column(name = "status_code")
    private String statusCode;

    @Column(name = "pm_reservation_id")
    private String pmReservationId;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    private SyncStatus syncStatus;

    @Column(name = "last_error")
    private String lastError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_request", columnDefinition = "jsonb")
    private JsonNode lastRequest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_response", columnDefinition = "jsonb")
    private JsonNode lastResponse;

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum SyncStatus {
        PENDING, SYNCED, FAILED
    }
}
