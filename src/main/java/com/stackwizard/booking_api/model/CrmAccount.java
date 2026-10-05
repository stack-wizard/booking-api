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

@Entity
@Table(name = "crm_account")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmAccount extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "legal_name")
    private String legalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    private String segment;

    @Column(name = "vat_id")
    private String vatId;

    @Column(name = "parent_account_id")
    private Long parentAccountId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "team_id")
    private Long teamId;

    private String email;
    private String phone;
    private String website;

    @Column(name = "address_line")
    private String addressLine;

    private String city;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(length = 2)
    private String country;

    @Column(nullable = false)
    private Boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb", nullable = false)
    private JsonNode attrs;

    public enum AccountType {
        COMPANY, AGENCY, ASSOCIATION, PERSON
    }
}
