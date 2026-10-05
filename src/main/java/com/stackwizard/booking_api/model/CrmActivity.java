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
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "crm_activity")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmActivity extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false)
    private ActivityType activityType;

    @Column(nullable = false)
    private String subject;

    private String body;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "contact_id")
    private Long contactId;

    @Column(name = "opportunity_id")
    private Long opportunityId;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "done_at")
    private OffsetDateTime doneAt;

    public enum ActivityType {
        CALL, EMAIL, MEETING, TASK, NOTE
    }
}
