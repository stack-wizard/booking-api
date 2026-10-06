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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Table(name = "sales_quote")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesQuote extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "opportunity_id")
    private Long opportunityId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "quote_number", nullable = false)
    private String quoteNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String currency;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "total_base", nullable = false)
    private BigDecimal totalBase;

    @Column(name = "total_offered", nullable = false)
    private BigDecimal totalOffered;

    @Column(name = "total_tax", nullable = false)
    private BigDecimal totalTax;

    @Column(name = "discount_percent", nullable = false)
    private BigDecimal discountPercent;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(columnDefinition = "text")
    private String terms;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decided_by_name")
    private String decidedByName;

    @Column(name = "decision_note", columnDefinition = "text")
    private String decisionNote;

    public enum Status {
        DRAFT, PENDING_APPROVAL, APPROVED, SENT, ACCEPTED, REJECTED, EXPIRED, SUPERSEDED;

        public static final Set<Status> OPEN = EnumSet.of(DRAFT, PENDING_APPROVAL, APPROVED, SENT);

        public boolean isEditable() {
            return this == DRAFT || this == APPROVED;
        }
    }
}
