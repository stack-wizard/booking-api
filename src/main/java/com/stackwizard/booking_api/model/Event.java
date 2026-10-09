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

import java.time.LocalDate;

@Entity
@Table(name = "event")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "primary_contact_id")
    private Long primaryContactId;

    @Column(name = "opportunity_id")
    private Long opportunityId;

    /** Hotel the event is limited to; null = chain-wide, functions may use any hotel of the chain. */
    @Column(name = "property_tenant_id")
    private Long propertyTenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "decision_date")
    private LocalDate decisionDate;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Column(name = "expected_pax")
    private Integer expectedPax;

    @Column(name = "guaranteed_pax")
    private Integer guaranteedPax;

    @Column(name = "guarantee_due_date")
    private LocalDate guaranteeDueDate;

    @Column(name = "actual_pax")
    private Integer actualPax;

    @Column(nullable = false)
    private String currency;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "outcome_reason_id")
    private Long outcomeReasonId;

    @Column(name = "outcome_note")
    private String outcomeNote;

    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb", nullable = false)
    private JsonNode attrs;

    public enum Status {
        INQUIRY, TENTATIVE, DEFINITE, ACTUAL, TURNED_DOWN, LOST, CANCELLED;

        public boolean isClosed() {
            return this == ACTUAL || this == TURNED_DOWN || this == LOST || this == CANCELLED;
        }

        public boolean holdsSpace() {
            return this == TENTATIVE || this == DEFINITE;
        }
    }
}
