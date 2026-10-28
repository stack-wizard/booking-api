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
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "crm_contact")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmContact extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    private String email;
    private String phone;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "platform_user_id")
    private UUID platformUserId;

    /** OPERA guest profile id; set by the OPERA integration, not by the contact form. */
    @Column(name = "opera_profile_id")
    private String operaProfileId;

    @Column(nullable = false)
    private Boolean active;

    /** B2B contact (sales) or GUEST (PMS profile / loyalty member); one person table for every module. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    private String nationality;
    private String country;
    private String city;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "address_line")
    private String addressLine;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String gender;
    private String language;

    @Column(name = "vip_code")
    private String vipCode;

    @Column(name = "merged_into_id")
    private Long mergedIntoId;

    @Column(name = "pms_opening_stays", nullable = false)
    private Integer pmsOpeningStays;

    @Column(name = "pms_opening_nights", nullable = false)
    private Integer pmsOpeningNights;

    @Column(name = "pms_opening_revenue", nullable = false)
    private BigDecimal pmsOpeningRevenue;

    @Column(name = "pms_opening_as_of")
    private LocalDate pmsOpeningAsOf;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pms_stats", columnDefinition = "jsonb", nullable = false)
    private JsonNode pmsStats;

    @Column(name = "stays_total", nullable = false)
    private Integer staysTotal;

    @Column(name = "nights_total", nullable = false)
    private Integer nightsTotal;

    @Column(name = "revenue_total", nullable = false)
    private BigDecimal revenueTotal;

    @Column(name = "room_revenue_total", nullable = false)
    private BigDecimal roomRevenueTotal;

    @Column(name = "first_stay")
    private LocalDate firstStay;

    @Column(name = "last_stay")
    private LocalDate lastStay;

    @Column(name = "next_arrival")
    private LocalDate nextArrival;

    @Column(name = "favorite_property_tenant_id")
    private Long favoritePropertyTenantId;

    @Column(name = "stats_updated_at")
    private OffsetDateTime statsUpdatedAt;

    public enum Kind {
        B2B, GUEST
    }

    public enum Source {
        MANUAL, OPERA, IMPORT, WEB, BOOKING
    }

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb", nullable = false)
    private JsonNode attrs;
}
