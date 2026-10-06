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

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "crm_alert")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "quote_id")
    private Long quoteId;

    @Column(name = "milestone_id")
    private Long milestoneId;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;

    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Kind {
        DECISION_DATE_DUE, GUARANTEE_DUE, BEO_NOT_ISSUED, QUOTE_EXPIRED, MILESTONE_DUE, QUOTE_DECIDED
    }
}
