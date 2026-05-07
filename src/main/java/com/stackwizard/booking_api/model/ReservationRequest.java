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

import java.time.OffsetDateTime;

@Entity
@Table(name = "reservation_request")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "customer_country", length = 2)
    private String customerCountry;

    @Column(name = "notes")
    private String notes;

    @Column(name = "external_reservation")
    private String externalReservation;

    @Column(name = "confirmation_code")
    private String confirmationCode;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "confirmation_email_sent_at")
    private OffsetDateTime confirmationEmailSentAt;

    @Column(name = "cancellation_policy_text")
    private String cancellationPolicyText;

    @Column(name = "extension_count", nullable = false)
    private Integer extensionCount;

    @Column(name = "opera_profile_id")
    private String operaProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "opera_deposit_post_status")
    private OperaDepositPostStatus operaDepositPostStatus;

    @Column(name = "opera_deposit_post_at")
    private OffsetDateTime operaDepositPostAt;

    @Column(name = "opera_deposit_post_error")
    private String operaDepositPostError;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Type {
        INTERNAL,
        EXTERNAL,
        WALKIN,
        INHOUSE
    }

    public enum Status {
        DRAFT,
        PENDING_PAYMENT,
        MANUAL_REVIEW,
        FINALIZED,
        CHECKED_IN,
        CHECKED_OUT,
        CANCELLED,
        EXPIRED;

        /**
         * Guest-facing confirmed booking lifecycle (finalized through end of stay).
         */
        public boolean isConfirmedStay() {
            return this == FINALIZED || this == CHECKED_IN || this == CHECKED_OUT;
        }
    }
}
