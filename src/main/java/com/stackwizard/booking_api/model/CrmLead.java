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

import java.time.OffsetDateTime;

@Entity
@Table(name = "crm_lead")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmLead extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String email;
    private String phone;
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "team_id")
    private Long teamId;

    private String segment;

    @Column(length = 2)
    private String country;

    @Column(name = "assignment_rule_id")
    private Long assignmentRuleId;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    private String description;

    @Column(name = "disqualify_reason_id")
    private Long disqualifyReasonId;

    @Column(name = "converted_account_id")
    private Long convertedAccountId;

    @Column(name = "converted_contact_id")
    private Long convertedContactId;

    @Column(name = "converted_opportunity_id")
    private Long convertedOpportunityId;

    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb", nullable = false)
    private JsonNode attrs;

    public enum Status {
        NEW, WORKING, QUALIFIED, DISQUALIFIED, CONVERTED
    }
}
