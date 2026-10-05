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
@Table(name = "crm_account_contact_role")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmAccountContactRole {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "contact_id", nullable = false)
    private Long contactId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "primary_contact", nullable = false)
    private Boolean primaryContact;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Role {
        DECISION_MAKER, BILLING, ON_SITE, TECHNICAL, OTHER
    }
}
