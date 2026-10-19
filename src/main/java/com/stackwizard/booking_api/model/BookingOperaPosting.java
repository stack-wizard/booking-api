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

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "booking_opera_posting")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingOperaPosting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "booking_opera_block_id", nullable = false)
    private Long bookingOperaBlockId;

    @Column(name = "booking_function_id")
    private Long bookingFunctionId;

    @Column(name = "booking_function_item_id")
    private Long bookingFunctionItemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    private String description;

    @Column(name = "trx_code")
    private String trxCode;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "last_error")
    private String lastError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request", columnDefinition = "jsonb")
    private JsonNode request;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response", columnDefinition = "jsonb")
    private JsonNode response;

    @Column(name = "posted_at")
    private OffsetDateTime postedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Source {
        ITEM, RENTAL
    }

    public enum Status {
        POSTED, FAILED
    }
}
