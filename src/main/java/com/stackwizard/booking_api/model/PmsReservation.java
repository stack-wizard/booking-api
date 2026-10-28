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
@Table(name = "pms_reservation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PmsReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_tenant_id", nullable = false)
    private Long propertyTenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pms_system", nullable = false)
    private PmsSystem pmsSystem;

    @Column(name = "pms_reservation_id", nullable = false)
    private String pmsReservationId;

    @Column(name = "confirmation_no")
    private String confirmationNo;

    @Column(name = "confirmation_leg_no")
    private Integer confirmationLegNo;

    @Column(name = "parent_pms_reservation_id")
    private String parentPmsReservationId;

    @Column(name = "external_reference")
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "status_raw")
    private String statusRaw;

    @Column(name = "arrival", nullable = false)
    private LocalDate arrival;

    @Column(name = "departure", nullable = false)
    private LocalDate departure;

    @Column(name = "original_departure")
    private LocalDate originalDeparture;

    @Column(name = "actual_check_in")
    private OffsetDateTime actualCheckIn;

    @Column(name = "actual_check_out")
    private OffsetDateTime actualCheckOut;

    @Column(name = "nights", nullable = false)
    private Integer nights;

    @Column(name = "units", nullable = false)
    private Integer units;

    @Column(name = "adults")
    private Integer adults;

    @Column(name = "children")
    private Integer children;

    @Column(name = "booking_date")
    private LocalDate bookingDate;

    @Column(name = "cancelled_at")
    private LocalDate cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "room_no")
    private String roomNo;

    @Column(name = "room_type")
    private String roomType;

    @Column(name = "room_type_charged")
    private String roomTypeCharged;

    @Column(name = "rate_code")
    private String rateCode;

    @Column(name = "source_code")
    private String sourceCode;

    @Column(name = "market_code")
    private String marketCode;

    @Column(name = "origin_code")
    private String originCode;

    @Column(name = "channel_code")
    private String channelCode;

    @Column(name = "block_code")
    private String blockCode;

    @Column(name = "guarantee_code")
    private String guaranteeCode;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "is_day_use", nullable = false)
    private Boolean isDayUse;

    @Column(name = "is_complimentary", nullable = false)
    private Boolean isComplimentary;

    @Column(name = "is_house_use", nullable = false)
    private Boolean isHouseUse;

    @Column(name = "is_pseudo_room", nullable = false)
    private Boolean isPseudoRoom;

    @Column(name = "is_walk_in", nullable = false)
    private Boolean isWalkIn;

    @Column(name = "holder_first_name")
    private String holderFirstName;

    @Column(name = "holder_last_name")
    private String holderLastName;

    @Column(name = "holder_email_norm")
    private String holderEmailNorm;

    @Column(name = "holder_phone_norm")
    private String holderPhoneNorm;

    @Column(name = "holder_nationality")
    private String holderNationality;

    @Column(name = "holder_pms_profile_id")
    private String holderPmsProfileId;

    @Column(name = "membership_type")
    private String membershipType;

    @Column(name = "membership_no")
    private String membershipNo;

    @Column(name = "membership_level")
    private String membershipLevel;

    @Column(name = "company_pms_profile_id")
    private String companyPmsProfileId;

    @Column(name = "agent_pms_profile_id")
    private String agentPmsProfileId;

    @Column(name = "group_pms_profile_id")
    private String groupPmsProfileId;

    @Column(name = "rate_amount")
    private BigDecimal rateAmount;

    @Column(name = "room_revenue")
    private BigDecimal roomRevenue;

    @Column(name = "fb_revenue")
    private BigDecimal fbRevenue;

    @Column(name = "other_revenue")
    private BigDecimal otherRevenue;

    @Column(name = "tax_total")
    private BigDecimal taxTotal;

    @Column(name = "total_revenue")
    private BigDecimal totalRevenue;

    @Column(name = "room_revenue_as_payee")
    private BigDecimal roomRevenueAsPayee;

    @Column(name = "currency")
    private String currency;

    @Column(name = "exchange_rate")
    private BigDecimal exchangeRate;

    @Column(name = "revenue_final", nullable = false)
    private Boolean revenueFinal;

    @Column(name = "contact_id")
    private Long contactId;

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

    @Column(name = "source_business_date")
    private LocalDate sourceBusinessDate;

    @Column(name = "export_as_of")
    private OffsetDateTime exportAsOf;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum PmsSystem {
        OPERA_V5, OPERA_CLOUD, BOOKING_API, CSV
    }

    public enum Status {
        PROSPECT, RESERVED, IN_HOUSE, CHECKED_OUT, CANCELLED, NO_SHOW, WAITLIST
    }

    public enum MatchMethod {
        PMS_PROFILE, MEMBERSHIP_NO, EMAIL, SECONDARY_EMAIL, PHONE, MANUAL
    }
}
