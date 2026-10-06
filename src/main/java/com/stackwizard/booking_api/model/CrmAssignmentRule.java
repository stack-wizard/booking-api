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
@Table(name = "crm_assignment_rule")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmAssignmentRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private Boolean active;

    private String segment;

    @Column(length = 2)
    private String country;

    private String source;

    @Column(name = "inquiry_type")
    private String inquiryType;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "min_pax")
    private Integer minPax;

    @Column(name = "max_pax")
    private Integer maxPax;

    @Column(name = "target_team_id", nullable = false)
    private Long targetTeamId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Strategy strategy;

    @Column(name = "fixed_user_id")
    private Long fixedUserId;

    @Column(name = "last_assigned_user_id")
    private Long lastAssignedUserId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum Strategy {
        /** Next active member after the last one assigned by this rule. */
        ROUND_ROBIN,
        /** Active member with the fewest open leads. */
        LEAST_LOADED,
        FIXED_USER,
        /** Team only; the team lead picks the owner. */
        QUEUE
    }
}
