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
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "loyalty_reservation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(name = "property_tenant_id", nullable = false)
    private Long propertyTenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "external_system", nullable = false)
    private ExternalSystem externalSystem;

    @Column(name = "confirmation_no", nullable = false)
    private String confirmationNo;

    @Column(name = "external_sub_id", nullable = false)
    private String externalSubId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "arrival", nullable = false)
    private LocalDate arrival;

    @Column(name = "departure", nullable = false)
    private LocalDate departure;

    @Column(name = "nights", nullable = false)
    private Integer nights;

    @Column(name = "units", nullable = false)
    private Integer units;

    @Column(name = "room_type")
    private String roomType;

    @Column(name = "source_code")
    private String sourceCode;

    @Column(name = "market_code")
    private String marketCode;

    @Column(name = "channel_code")
    private String channelCode;

    @Column(name = "rate_code")
    private String rateCode;

    @Column(name = "holder_first_name")
    private String holderFirstName;

    @Column(name = "holder_last_name")
    private String holderLastName;

    @Column(name = "holder_email_norm")
    private String holderEmailNorm;

    @Column(name = "holder_phone_norm")
    private String holderPhoneNorm;

    @Column(name = "membership_no")
    private String membershipNo;

    @Column(name = "exclusivity_flag", nullable = false)
    private Boolean exclusivityFlag;

    @Column(name = "cancelled_at")
    private LocalDate cancelledAt;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "currency")
    private String currency;

    @Column(name = "member_id")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_method")
    private MatchMethod matchMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw", nullable = false, columnDefinition = "jsonb")
    private JsonNode raw;

    @Column(name = "first_seen_batch_id")
    private Long firstSeenBatchId;

    @Column(name = "last_seen_batch_id")
    private Long lastSeenBatchId;

    @Column(name = "export_as_of")
    private OffsetDateTime exportAsOf;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum ExternalSystem {
        OPERA_V5, OPERA_CLOUD, BOOKING_API, CSV
    }

    public enum Status {
        RESERVED, IN_HOUSE, CHECKED_OUT, CANCELLED, NO_SHOW
    }

    public enum MatchMethod {
        MEMBERSHIP_NO, EMAIL, SECONDARY_EMAIL, MANUAL
    }
}
